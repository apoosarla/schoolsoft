package com.schoolsoft.admissions.internal;

import com.schoolsoft.admissions.api.AdmissionApplicationDto;
import com.schoolsoft.admissions.api.PublicAdmissions;
import com.schoolsoft.admissions.api.AdmissionEventDto;
import com.schoolsoft.enrolment.api.RollNumbers;
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
public class AdmissionsRepository implements PublicAdmissions {

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

    private static final String COLS =
        "id, school_id, academic_year_id, grade_id, application_no, applicant_first_name, applicant_last_name, " +
        "applicant_dob, applicant_gender, guardian_name, guardian_phone, guardian_email, source, state, " +
        "test_score, interview_notes, offer_expires_on, converted_student_id, created_at";

    public List<AdmissionApplicationDto> list(UUID schoolId, String state) {
        String sql = "SELECT " + COLS + " FROM admission_application WHERE school_id = ?" +
            (state == null ? "" : " AND state = ?") + " ORDER BY created_at DESC";
        return state == null ? jdbc.query(sql, MAPPER, schoolId) : jdbc.query(sql, MAPPER, schoolId, state);
    }

    public Optional<AdmissionApplicationDto> find(UUID id) {
        var rows = jdbc.query("SELECT " + COLS + " FROM admission_application WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<AdmissionApplicationDto> findByApplicationNoAndPhone(String applicationNo, String guardianPhone) {
        var rows = jdbc.query(
            "SELECT " + COLS + " FROM admission_application WHERE application_no = ? AND guardian_phone = ?",
            MAPPER, applicationNo, guardianPhone
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public AdmissionApplicationDto create(
        UUID schoolId, UUID academicYearId, UUID gradeId, String applicationNo,
        String firstName, String lastName, LocalDate dob, String gender,
        String guardianName, String guardianPhone, String guardianEmail, String source
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO admission_application (id, school_id, academic_year_id, grade_id, application_no, " +
            "  applicant_first_name, applicant_last_name, applicant_dob, applicant_gender, " +
            "  guardian_name, guardian_phone, guardian_email, source) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, academicYearId, gradeId, applicationNo, firstName, lastName,
            dob == null ? null : Date.valueOf(dob), gender, guardianName, guardianPhone, guardianEmail, source
        );
        recordEvent(id, "state_change", "lead", "lead", null);
        return find(id).orElseThrow();
    }

    public AdmissionApplicationDto transition(UUID id, String toState, UUID actorUserId) {
        var current = find(id).orElseThrow(() -> new NotFoundException("Application not found: " + id));
        jdbc.update("UPDATE admission_application SET state = ?, updated_at = now() WHERE id = ?", toState, id);
        recordEvent(id, "state_change", current.state(), toState, actorUserId);
        return find(id).orElseThrow();
    }

    public AdmissionApplicationDto recordTestScore(UUID id, double score, String notes) {
        jdbc.update(
            "UPDATE admission_application SET test_score = ?, interview_notes = ?, updated_at = now() WHERE id = ?",
            score, notes, id
        );
        recordEvent(id, "test_done", null, null, null);
        return find(id).orElseThrow(() -> new NotFoundException("Application not found: " + id));
    }

    public List<AdmissionEventDto> listEvents(UUID applicationId) {
        return jdbc.query(
            "SELECT id, application_id, event_type, from_state, to_state, occurred_at FROM admission_event " +
            "WHERE application_id = ? ORDER BY occurred_at",
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
     */
    public UUID convertToStudent(UUID applicationId, UUID sectionId, String rollNo) {
        return convertToStudent(applicationId, sectionId, rollNo, null);
    }

    /**
     * An offer against a full section is the same over-capacity decision as a
     * direct enrolment, so it goes through the same check (GAP-10), and the
     * admission and roll numbers come from the school's series (GAP-26) rather
     * than reusing the application number.
     */
    public UUID convertToStudent(UUID applicationId, UUID sectionId, String rollNo, String overCapacityReason) {
        var app = find(applicationId).orElseThrow(() -> new NotFoundException("Application not found: " + applicationId));
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

        jdbc.update(
            "UPDATE admission_application SET state = 'enrolled', converted_student_id = ?, updated_at = now() WHERE id = ?",
            studentId, applicationId
        );
        recordEvent(applicationId, "state_change", app.state(), "enrolled", null);
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
