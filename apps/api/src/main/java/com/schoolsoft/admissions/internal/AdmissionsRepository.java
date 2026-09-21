package com.schoolsoft.admissions.internal;

import com.schoolsoft.admissions.api.AdmissionApplicationDto;
import com.schoolsoft.admissions.api.AdmissionEventDto;
import com.schoolsoft.admissions.api.AdmissionFunnelSummaryDto;
import com.schoolsoft.admissions.api.AdmissionPolicyDto;
import com.schoolsoft.enrolment.api.RollNumbers;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.NumberSeries;
import com.schoolsoft.tenancy.api.SectionCapacity;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AdmissionsRepository {

    private final JdbcTemplate jdbc;
    private final SectionCapacity capacity;
    private final NumberSeries numbers;
    private final RollNumbers rollNumbers;

    public AdmissionsRepository(JdbcTemplate jdbc, SectionCapacity capacity, NumberSeries numbers,
                                RollNumbers rollNumbers) {
        this.jdbc = jdbc;
        this.capacity = capacity;
        this.numbers = numbers;
        this.rollNumbers = rollNumbers;
    }

    private static final RowMapper<AdmissionApplicationDto> MAPPER = (rs, i) -> new AdmissionApplicationDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("academic_year_id")),
        UUID.fromString(rs.getString("grade_id")),
        rs.getString("application_no"),
        rs.getString("applicant_first_name"),
        rs.getString("applicant_last_name"),
        rs.getDate("applicant_dob") == null ? null : rs.getDate("applicant_dob").toLocalDate(),
        rs.getString("applicant_gender"),
        rs.getString("guardian_name"),
        rs.getString("guardian_phone"),
        rs.getString("guardian_email"),
        rs.getString("source"),
        rs.getString("state"),
        com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "test_score"),
        rs.getString("interview_notes"),
        rs.getDate("offer_expires_on") == null ? null : rs.getDate("offer_expires_on").toLocalDate(),
        rs.getString("converted_student_id") == null ? null : UUID.fromString(rs.getString("converted_student_id")),
        rs.getTimestamp("created_at").toInstant()
    );

    /**
     * The funnel's states in funnel order. Mirrors the CHECK on
     * {@code admission_application.state} (V007) by hand — the database knows
     * the set but not the order, and the order is what makes a board read like
     * a pipeline rather than an alphabetical list.
     */
    public static final List<String> STATES = List.of(
        "lead", "application_started", "document_pending", "fee_pending", "review",
        "test_scheduled", "test_done", "offered", "accepted", "waitlist",
        "enrolled", "rejected", "lapsed");

    private static final String COLS =
        "id, school_id, academic_year_id, grade_id, application_no, applicant_first_name, applicant_last_name, " +
        "applicant_dob, applicant_gender, guardian_name, guardian_phone, guardian_email, source, state, " +
        "test_score, interview_notes, offer_expires_on, converted_student_id, created_at";

    public List<AdmissionApplicationDto> list(UUID schoolId, String state) {
        return list(schoolId, state, null, 0);
    }

    /**
     * One page of one stage. {@code limit} null means every row, which is what
     * the older callers pass and what an export needs; the pipeline screen
     * always names a page, because a closed year holds several thousand
     * applications and nobody reads them all at once.
     */
    public List<AdmissionApplicationDto> list(UUID schoolId, String state, Integer limit, int offset) {
        var args = new java.util.ArrayList<Object>();
        args.add(schoolId);
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM admission_application WHERE school_id = ?");
        if (state != null) {
            sql.append(" AND state = ?");
            args.add(state);
        }
        // created_at alone is not a total order -- two applications lodged in
        // the same millisecond would swap places between pages and one of them
        // would never be read. id breaks the tie.
        sql.append(" ORDER BY created_at DESC, id DESC");
        if (limit != null) {
            sql.append(" LIMIT ? OFFSET ?");
            args.add(limit);
            args.add(Math.max(0, offset));
        }
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * The funnel as counts. Every state appears, including the empty ones, so
     * the board keeps its shape as a season fills up.
     */
    public AdmissionFunnelSummaryDto summary(UUID schoolId, List<String> allStates) {
        var counted = new java.util.LinkedHashMap<String, Long>();
        allStates.forEach(state -> counted.put(state, 0L));

        jdbc.query(
            "SELECT state, count(*) AS n FROM admission_application WHERE school_id = ? GROUP BY state",
            rs -> {
                counted.merge(rs.getString("state"), rs.getLong("n"), Long::sum);
            },
            schoolId);

        long total = counted.values().stream().mapToLong(Long::longValue).sum();

        Long soon = jdbc.queryForObject(
            "SELECT count(*) FROM admission_application WHERE school_id = ? AND state = 'offered' "
            + "AND offer_expires_on IS NOT NULL AND offer_expires_on BETWEEN CURRENT_DATE AND CURRENT_DATE + 7",
            Long.class, schoolId);
        Long expired = jdbc.queryForObject(
            "SELECT count(*) FROM admission_application WHERE school_id = ? AND state = 'offered' "
            + "AND offer_expires_on IS NOT NULL AND offer_expires_on < CURRENT_DATE",
            Long.class, schoolId);

        var byState = counted.entrySet().stream()
            .map(e -> new AdmissionFunnelSummaryDto.StateCount(e.getKey(), e.getValue()))
            .toList();
        return new AdmissionFunnelSummaryDto(schoolId, total, byState,
            soon == null ? 0 : soon, expired == null ? 0 : expired);
    }

    public Optional<AdmissionApplicationDto> find(UUID id) {
        var rows = jdbc.query("SELECT " + COLS + " FROM admission_application WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<AdmissionApplicationDto> findByApplicationNoAndPhone(String applicationNo, String guardianPhone) {
        var rows = jdbc.query(
            "SELECT " + COLS + " FROM admission_application WHERE application_no = ? AND guardian_phone = ?",
            MAPPER, applicationNo, guardianPhone
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public AdmissionApplicationDto create(
        UUID schoolId, UUID academicYearId, UUID gradeId, String applicationNo,
        String firstName, String lastName, LocalDate dob, String gender,
        String guardianName, String guardianPhone, String guardianEmail, String source
    ) {
        UUID id = UUID.randomUUID();
        // Issued by the school's series, not by the caller (V019's rule). The
        // public site used to mint "WEB-" + a random UUID fragment and the
        // office typed them by hand, so numbers had no shape, no sequence and
        // could collide on the unique constraint. A caller may still pass one:
        // a back-office import carries numbers families already hold.
        String number = applicationNo == null || applicationNo.isBlank()
            ? numbers.next(schoolId, NumberSeries.Kind.application, null, "APP{YY}{SEQ:4}", null)
            : applicationNo;
        jdbc.update(
            "INSERT INTO admission_application (id, school_id, academic_year_id, grade_id, application_no, " +
            "  applicant_first_name, applicant_last_name, applicant_dob, applicant_gender, " +
            "  guardian_name, guardian_phone, guardian_email, source) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, academicYearId, gradeId, number, firstName, lastName,
            dob == null ? null : Date.valueOf(dob), gender, guardianName, guardianPhone, guardianEmail, source
        );
        // Not a state change: nothing moved, the file began. Typing it as
        // `lead -> lead` put a move in the trail that the machine itself
        // forbids (admission_transition CHECKs from_state <> to_state), and
        // every time-in-stage read over that trail had to special-case it.
        recordEvent(id, "enquiry_received", null, "lead", null);
        return find(id).orElseThrow();
    }

    // ------------------------------------------------------------- the policy

    /**
     * The funnel this school runs. Every school gets a row at migration time
     * and the row is never deleted, so a missing one means a school created
     * outside the normal path; it falls back to the same defaults the column
     * definitions carry rather than refusing the read.
     */
    public AdmissionPolicyDto policy(UUID schoolId) {
        var rows = jdbc.query(
            "SELECT school_id, entrance_test_required, offer_validity_days FROM admission_policy "
            + "WHERE school_id = ?",
            (rs, i) -> new AdmissionPolicyDto(
                UUID.fromString(rs.getString("school_id")),
                rs.getBoolean("entrance_test_required"),
                rs.getInt("offer_validity_days")),
            schoolId);
        return rows.isEmpty() ? new AdmissionPolicyDto(schoolId, true, 14) : rows.get(0);
    }

    public AdmissionPolicyDto savePolicy(UUID schoolId, boolean entranceTestRequired, int offerValidityDays) {
        jdbc.update(
            "INSERT INTO admission_policy (school_id, entrance_test_required, offer_validity_days, updated_at) "
            + "VALUES (?, ?, ?, now()) "
            + "ON CONFLICT (school_id) DO UPDATE SET entrance_test_required = EXCLUDED.entrance_test_required, "
            + "  offer_validity_days = EXCLUDED.offer_validity_days, updated_at = now()",
            schoolId, entranceTestRequired, offerValidityDays);
        return policy(schoolId);
    }

    // ------------------------------------------------------- the state machine

    /** One row of the machine: the move exists, and these are its conditions. */
    public record Move(String requiresPerm, Boolean requiresEntranceTest) {

        /**
         * A move with {@code requiresEntranceTest} NULL belongs to both funnels.
         * TRUE or FALSE means it belongs to one, and the school has to be
         * running that one.
         */
        public boolean fitsFunnel(boolean schoolTests) {
            return requiresEntranceTest == null || requiresEntranceTest == schoolTests;
        }
    }

    /**
     * The move from {@code fromState} to {@code toState}, or empty when the
     * machine has no such move at all. {@code AdmissionsService} is what acts on
     * it — the answer is a fact about the funnel, the decision to refuse is a
     * use case.
     */
    public Optional<Move> move(String fromState, String toState) {
        var rows = jdbc.query(
            "SELECT requires_perm, requires_entrance_test FROM admission_transition "
            + "WHERE from_state = ? AND to_state = ?",
            (rs, i) -> new Move(
                rs.getString("requires_perm"),
                rs.getObject("requires_entrance_test") == null ? null : rs.getBoolean("requires_entrance_test")),
            fromState, toState);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Every state this one may move to <em>at this school</em>, in the order a
     * reviewer would read them. A school with no entrance test never sees
     * {@code test_scheduled} offered to it.
     */
    public List<String> movesFrom(String fromState, boolean schoolTests) {
        return jdbc.query(
            "SELECT to_state FROM admission_transition "
            + "WHERE from_state = ? AND (requires_entrance_test IS NULL OR requires_entrance_test = ?) "
            + "ORDER BY note, to_state",
            (rs, i) -> rs.getString("to_state"), fromState, schoolTests);
    }

    /**
     * Moves the application out of {@code fromState}, and only out of that
     * state: reading the state and then writing unconditionally leaves a window
     * in which somebody else's move is overwritten. Zero rows means the
     * application is no longer where the caller thought it was, and the caller
     * decides whether that is a conflict or a retry that already succeeded.
     *
     * @return true when this call is the one that moved it
     */
    public boolean transitionFrom(UUID id, String fromState, String toState, UUID actorUserId,
                                  LocalDate offerExpiresOn) {
        // The expiry rides along in the same statement as the state it belongs
        // to: an offer that exists without a date on it is one nothing can ever
        // expire. COALESCE leaves the date alone on every other move, so a move
        // out of `offered` keeps the history of what the family was told.
        int moved = jdbc.update(
            "UPDATE admission_application SET state = ?, "
            + "  offer_expires_on = COALESCE(?, offer_expires_on), updated_at = now() "
            + "WHERE id = ? AND state = ?",
            toState, offerExpiresOn == null ? null : Date.valueOf(offerExpiresOn), id, fromState);
        if (moved == 0) return false;
        recordEvent(id, "state_change", fromState, toState, actorUserId);
        return true;
    }

    /**
     * Records the entrance-test result, which is also what moves the
     * application to {@code test_done} — the score and the state were allowed
     * to disagree before this, because the score was written unconditionally
     * and the state was left for somebody to move by hand.
     *
     * <p>Amending a score on an application already at {@code test_done} is a
     * correction and stays there. The correction is recorded as its own event
     * rather than a second state change, because nothing moved.</p>
     *
     * @return true when this call moved the application out of {@code test_scheduled}
     */
    public boolean recordTestScore(UUID id, double score, String notes, UUID actorUserId) {
        int scored = jdbc.update(
            "UPDATE admission_application SET test_score = ?, interview_notes = ?, "
            + "  state = 'test_done', updated_at = now() "
            + "WHERE id = ? AND state = 'test_scheduled'",
            score, notes, id);
        if (scored == 1) {
            recordEvent(id, "state_change", "test_scheduled", "test_done", actorUserId);
            return true;
        }
        int amended = jdbc.update(
            "UPDATE admission_application SET test_score = ?, interview_notes = ?, updated_at = now() "
            + "WHERE id = ? AND state = 'test_done'",
            score, notes, id);
        if (amended == 1) {
            recordEvent(id, "test_score_amended", null, null, actorUserId);
            return false;
        }
        var current = find(id).orElseThrow(() -> new NotFoundException("Application not found: " + id));
        throw new ConflictException(
            "Cannot record a test score against an application that is '" + current.state()
            + "' \u2014 a score is recorded while it is 'test_scheduled'.");
    }

    public List<AdmissionEventDto> listEvents(UUID applicationId) {
        return jdbc.query(
            // admission_event carries no school_id and so no RLS policy; the
            // join to the application is what bounds the trail to this school.
            "SELECT e.id, e.application_id, e.event_type, e.from_state, e.to_state, e.occurred_at " +
            "FROM admission_event e JOIN admission_application app ON app.id = e.application_id " +
            "WHERE e.application_id = ? ORDER BY e.occurred_at",
            (rs, i) -> new AdmissionEventDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("application_id")),
                rs.getString("event_type"),
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getTimestamp("occurred_at").toInstant()
            ),
            applicationId
        );
    }

    private void recordEvent(UUID applicationId, String eventType, String fromState, String toState, UUID actorUserId) {
        jdbc.update(
            "INSERT INTO admission_event (id, application_id, event_type, from_state, to_state, actor_user_id) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), applicationId, eventType, fromState, toState, actorUserId
        );
    }

    /**
     * Converts an accepted application into a {@code student} row plus an
     * active {@code enrolment} in {@code sectionId}. Marks the application
     * {@code enrolled}. Direct SQL against {@code student}/{@code enrolment}
     * (owned by the people/enrolment modules) mirrors the existing pattern of
     * cross-cutting reads elsewhere in this codebase (e.g. PeopleRepository
     * joining section/grade) rather than introducing a Java dependency.
     *
     * <p>An offer against a full section is the same over-capacity decision as
     * a direct enrolment, so it goes through the same check (GAP-10), and the
     * admission and roll numbers come from the school's series (GAP-26) rather
     * than reusing the application number.</p>
     */
    public UUID convertToStudent(UUID applicationId, UUID sectionId, String rollNo, String overCapacityReason) {
        var app = find(applicationId).orElseThrow(() -> new NotFoundException("Application not found: " + applicationId));
        // Conversion is a transition like any other (`accepted -> enrolled`),
        // so it answers to the same machine. Checked before the student is
        // created rather than only at the final UPDATE: a refusal here must not
        // leave a student row behind, and the guard reads as the precondition
        // it is. Re-running a conversion that already happened returns the
        // student it made, because a retry is not an error.
        if ("enrolled".equals(app.state()) && app.convertedStudentId() != null) {
            return app.convertedStudentId();
        }
        if (!"accepted".equals(app.state())) {
            throw new ConflictException(
                "Cannot enrol an application that is '" + app.state() + "' — a seat is confirmed from "
                + "'accepted'. Move the application through the funnel first.");
        }
        String override = capacity.reserveSeat(sectionId, overCapacityReason);
        UUID studentId = UUID.randomUUID();
        String admissionNo = numbers.next(app.schoolId(), NumberSeries.Kind.admission, null, "ADM{YY}{SEQ:4}", null);
        String roll = rollNumbers.nextFor(app.schoolId(), sectionId, rollNo);
        jdbc.update(
            "INSERT INTO student (id, school_id, admission_no, first_name, middle_name, last_name, dob, gender, status) " +
            "VALUES (?, ?, ?, ?, NULL, ?, ?, ?, 'active')",
            studentId, app.schoolId(), admissionNo, app.applicantFirstName(), app.applicantLastName(),
            app.applicantDob() == null ? null : Date.valueOf(app.applicantDob()), app.applicantGender()
        );
        jdbc.update(
            "INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, starts_on, status, " +
            "  roll_no, over_capacity_reason) VALUES (?, ?, ?, ?, ?, CURRENT_DATE, 'active', ?, ?)",
            UUID.randomUUID(), app.schoolId(), studentId, sectionId, app.academicYearId(), roll, override
        );
        // The family that applied has to exist as a login and as a household, or
        // the guardian cannot see this child and no sibling rule can find them
        // (ADM-10, ADM-11).
        linkGuardian(app.schoolId(), studentId, app.guardianName(), app.guardianPhone(), app.guardianEmail());

        int enrolled = jdbc.update(
            "UPDATE admission_application SET state = 'enrolled', converted_student_id = ?, updated_at = now() " +
            "WHERE id = ? AND state = 'accepted'",
            studentId, applicationId
        );
        if (enrolled == 0) {
            // Somebody moved the application between the guard above and here.
            // AdmissionsService.enrol owns the transaction, so throwing takes
            // the student and the enrolment with it.
            throw new ConflictException(
                "Cannot enrol " + applicationId + " — it left 'accepted' while the seat was being confirmed.");
        }
        recordEvent(applicationId, "state_change", "accepted", "enrolled", null);
        return studentId;
    }

    /**
     * Attaches the applicant's guardian to the new student, reusing the
     * guardian record (and their login) when the phone number is already known
     * — which is exactly what happens when a second child of the same family
     * applies.
     */
    private void linkGuardian(UUID schoolId, UUID studentId, String guardianName, String guardianPhone,
                              String guardianEmail) {
        if (guardianPhone == null || guardianPhone.isBlank()) return;

        var existing = jdbc.query(
            "SELECT id FROM guardian WHERE school_id = ? AND phone = ? LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString("id")), schoolId, guardianPhone);

        UUID guardianId;
        if (!existing.isEmpty()) {
            guardianId = existing.get(0);
        } else {
            guardianId = UUID.randomUUID();
            String first = guardianName == null || guardianName.isBlank() ? "Guardian"
                : guardianName.trim().split("\\s+")[0];
            String last = guardianName == null || !guardianName.trim().contains(" ") ? null
                : guardianName.trim().substring(guardianName.trim().indexOf(' ') + 1);
            jdbc.update(
                "INSERT INTO guardian (id, school_id, first_name, last_name, phone, email) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                guardianId, schoolId, first, last, guardianPhone, guardianEmail);
            jdbc.update(
                "INSERT INTO user_account (id, school_id, subject_type, subject_id, phone, email) " +
                "VALUES (?, ?, 'guardian', ?, ?, ?)",
                UUID.randomUUID(), schoolId, guardianId, guardianPhone, guardianEmail);
        }

        boolean firstChild = jdbc.queryForObject(
            "SELECT count(*) FROM guardian_student WHERE guardian_id = ?", Integer.class, guardianId) == 0;
        jdbc.update(
            "INSERT INTO guardian_student (guardian_id, student_id, relation, is_primary) " +
            "VALUES (?, ?, 'guardian', ?) ON CONFLICT DO NOTHING",
            guardianId, studentId, firstChild);
    }
}
