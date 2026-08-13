package com.schoolsoft.assessment.internal;

import com.schoolsoft.assessment.api.MarkDto;
import com.schoolsoft.assessment.api.MarkReevaluationDto;
import com.schoolsoft.assessment.api.MarkRevisionDto;
import com.schoolsoft.enrolment.api.SubjectSetResolver;
import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.platform.db.Jdbc;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.AcademicYearGuard;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything that writes a mark (GAP-06).
 *
 * Four rules the gradebook did not have and a school cannot run without:
 *
 * <ul>
 *   <li>a mark above the component's maximum is a typo, and is refused rather
 *       than stored and discovered on a report card;</li>
 *   <li>a blank is not a zero — an unmarked paper, an absence, a medical
 *       absence and an exemption are four different states, and only one of
 *       them carries a number;</li>
 *   <li>a locked assessment refuses writes. The lifecycle statuses existed
 *       before this and meant nothing: {@code enterMark} upserted regardless
 *       (ASMT-06);</li>
 *   <li>a changed mark keeps its predecessor. Moderation and re-evaluation
 *       supersede; nothing overwrites (ASMT-07, ASMT-08).</li>
 * </ul>
 */
@Service
public class MarkService {

    /** Statuses in which the marks are final and writes are refused. */
    static final List<String> SEALED = List.of("locked", "published");

    /** A mark's status: only {@code entered} carries a number. */
    private static final List<String> STATUSES =
        List.of("entered", "pending", "absent", "medical_leave", "exempt");

    /** Roles that may decide a re-evaluation or reopen sealed marks. */
    static final List<String> EXAM_AUTHORITY_ROLES =
        List.of("principal", "vice_principal", "exams_officer", "academic_coordinator", "it_admin");

    private final JdbcTemplate jdbc;
    private final AcademicYearGuard academicYears;
    private final SubjectSetResolver subjectSets;
    private final Authz authz;
    private final ReportCardService reportCards;

    public MarkService(JdbcTemplate jdbc, AcademicYearGuard academicYears, SubjectSetResolver subjectSets,
                       Authz authz, ReportCardService reportCards) {
        this.jdbc = jdbc;
        this.academicYears = academicYears;
        this.subjectSets = subjectSets;
        this.authz = authz;
        this.reportCards = reportCards;
    }

    // ------------------------------------------------------------------ reads

    static final String MARK_COLS =
        "m.id, m.assessment_component_id, m.student_id, m.raw_marks, m.grade_letter, m.remarks, m.status, " +
        "(SELECT count(*) FROM mark_revision r WHERE r.mark_id = m.id) AS revision_count";

    static final RowMapper<MarkDto> MARK_MAPPER = (rs, i) -> MarkDto.of(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("assessment_component_id")),
        UUID.fromString(rs.getString("student_id")),
        Jdbc.nullableDouble(rs, "raw_marks"),
        rs.getString("grade_letter"),
        rs.getString("remarks"),
        rs.getString("status"),
        rs.getInt("revision_count"));

    public List<MarkDto> listMarks(UUID componentId) {
        return jdbc.query("SELECT " + MARK_COLS + " FROM mark m WHERE m.assessment_component_id = ? " +
            "ORDER BY m.student_id", MARK_MAPPER, componentId);
    }

    public Optional<MarkDto> find(UUID markId) {
        return jdbc.query("SELECT " + MARK_COLS + " FROM mark m WHERE m.id = ?", MARK_MAPPER, markId)
            .stream().findFirst();
    }

    public List<MarkRevisionDto> revisions(UUID markId) {
        return jdbc.query(
            "SELECT id, mark_id, revision_no, kind, old_raw_marks, old_status, old_grade_letter, " +
            "       new_raw_marks, new_status, new_grade_letter, reason, changed_by_user_id, changed_at " +
            "FROM mark_revision WHERE mark_id = ? ORDER BY revision_no",
            (rs, i) -> new MarkRevisionDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("mark_id")),
                rs.getInt("revision_no"),
                rs.getString("kind"),
                Jdbc.nullableDouble(rs, "old_raw_marks"),
                rs.getString("old_status"),
                rs.getString("old_grade_letter"),
                Jdbc.nullableDouble(rs, "new_raw_marks"),
                rs.getString("new_status"),
                rs.getString("new_grade_letter"),
                rs.getString("reason"),
                rs.getString("changed_by_user_id") == null ? null
                    : UUID.fromString(rs.getString("changed_by_user_id")),
                rs.getTimestamp("changed_at").toInstant()),
            markId);
    }

    // ----------------------------------------------------------------- writes

    /** One entry in a bulk submission; {@code status} may be left null. */
    public record MarkEntry(UUID studentId, Double rawMarks, String status, String gradeLetter, String remarks) {}

    /** What a bulk submission did, and what it refused (ASMT-04). */
    public record BulkResult(UUID componentId, int accepted, List<MarkDto> marks, List<Rejection> rejected) {
        public record Rejection(UUID studentId, String reason) {}
    }

    /**
     * Bulk entry for a section. Each row is validated on its own and the good
     * ones are stored: one typo in a class of forty must not throw away the
     * other thirty-nine, but it must not be stored either.
     */
    @Transactional
    public BulkResult enterBulk(UUID schoolId, UUID componentId, List<MarkEntry> entries, UUID enteredByStaffId,
                                String reason) {
        Component component = component(componentId);
        // Year first, lifecycle second: a closed year refuses the write outright,
        // and telling the caller to reopen an assessment inside it would send
        // them down a path that ends in the same refusal (GAP-14).
        academicYears.requireOpenForAssessmentComponent(componentId);
        requireWritable(component);

        List<MarkDto> stored = new ArrayList<>();
        List<BulkResult.Rejection> rejected = new ArrayList<>();
        for (MarkEntry entry : entries) {
            try {
                stored.add(write(schoolId, component, entry, enteredByStaffId, "correction",
                    reason == null || reason.isBlank() ? "Bulk mark entry" : reason));
            } catch (RuntimeException e) {
                rejected.add(new BulkResult.Rejection(entry.studentId(), e.getMessage()));
            }
        }
        return new BulkResult(componentId, stored.size(), stored, rejected);
    }

    /** Single-mark entry — the same rules, one row at a time. */
    @Transactional
    public MarkDto enter(UUID schoolId, UUID componentId, MarkEntry entry, UUID enteredByStaffId, String reason) {
        Component component = component(componentId);
        // Year first, lifecycle second: a closed year refuses the write outright,
        // and telling the caller to reopen an assessment inside it would send
        // them down a path that ends in the same refusal (GAP-14).
        academicYears.requireOpenForAssessmentComponent(componentId);
        requireWritable(component);
        return write(schoolId, component, entry, enteredByStaffId, "correction",
            reason == null || reason.isBlank() ? "Mark entry" : reason);
    }

    private MarkDto write(UUID schoolId, Component component, MarkEntry entry, UUID enteredByStaffId,
                          String revisionKind, String reason) {
        if (entry.studentId() == null) throw new IllegalArgumentException("Mark entry has no student");

        // A mark only means something if the student takes the subject. With
        // electives, the section no longer answers that — the student's own
        // subject set does (GAP-05).
        subjectSets.requireStudies(entry.studentId(), component.subjectId(), component.onDate());

        String status = normaliseStatus(entry);
        Double rawMarks = "entered".equals(status) ? entry.rawMarks() : null;
        if (rawMarks != null) {
            if (rawMarks < 0) {
                throw new IllegalArgumentException("Mark " + rawMarks + " is negative");
            }
            if (rawMarks > component.maxMarks() + 1e-9) {
                throw new IllegalArgumentException(
                    "Mark " + rawMarks + " exceeds the component maximum of " + component.maxMarks());
            }
        }

        Existing existing = existing(component.id(), entry.studentId());
        UUID markId = existing == null ? UUID.randomUUID() : existing.id();

        if (existing == null) {
            jdbc.update(
                "INSERT INTO mark (id, school_id, assessment_component_id, student_id, raw_marks, grade_letter, " +
                "                  remarks, status, entered_by_staff_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                markId, schoolId, component.id(), entry.studentId(), rawMarks, entry.gradeLetter(),
                entry.remarks(), status, enteredByStaffId);
        } else {
            boolean changed = !java.util.Objects.equals(existing.rawMarks(), rawMarks)
                || !java.util.Objects.equals(existing.status(), status)
                || !java.util.Objects.equals(existing.gradeLetter(), entry.gradeLetter());
            jdbc.update(
                "UPDATE mark SET raw_marks = ?, grade_letter = ?, remarks = ?, status = ?, " +
                "  entered_by_staff_id = COALESCE(?, entered_by_staff_id), entered_at = now() WHERE id = ?",
                rawMarks, entry.gradeLetter(), entry.remarks(), status, enteredByStaffId, markId);
            if (changed) {
                recordRevision(schoolId, markId, revisionKind, existing, rawMarks, status,
                    entry.gradeLetter(), reason);
            }
        }
        return find(markId).orElseThrow();
    }

    /**
     * A caller may name the status outright; when they do not, the shape of
     * the submission says it. A number is a mark — zero included — and a blank
     * is a paper nobody has marked yet, not a zero.
     */
    private String normaliseStatus(MarkEntry entry) {
        String status = entry.status();
        if (status == null || status.isBlank()) {
            return entry.rawMarks() == null ? "pending" : "entered";
        }
        status = status.trim().toLowerCase();
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unknown mark status '" + status + "'; expected one of " + STATUSES);
        }
        if ("entered".equals(status) && entry.rawMarks() == null) {
            throw new IllegalArgumentException("Status 'entered' needs a mark; use 'pending' for an unmarked paper");
        }
        return status;
    }

    // ------------------------------------------------------- re-evaluation

    /**
     * A family asks for a paper to be looked at again (ASMT-08). Guardians may
     * only ask about their own child; staff may raise one on a family's behalf,
     * which is how a phone call reaches the same workflow.
     */
    @Transactional
    public MarkReevaluationDto requestReevaluation(UUID markId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A re-evaluation request needs a reason");
        }
        var mark = find(markId).orElseThrow(() -> new NotFoundException("Mark not found: " + markId));
        UUID schoolId = jdbc.queryForObject("SELECT school_id FROM mark WHERE id = ?", UUID.class, markId);
        requireGuardianOfOrStaff(mark.studentId());

        UUID id = UUID.randomUUID();
        try {
            jdbc.update(
                "INSERT INTO mark_reevaluation (id, school_id, mark_id, student_id, reason, requested_by_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                id, schoolId, markId, mark.studentId(), reason, currentUserId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ConflictException("A re-evaluation of this mark is already pending");
        }
        return reevaluation(id);
    }

    /**
     * The school's answer. {@code revised} supersedes the mark and keeps the
     * original; {@code upheld} and {@code rejected} change nothing but are
     * recorded, because a family is owed the fact that somebody looked.
     */
    @Transactional
    public MarkReevaluationDto decideReevaluation(UUID id, String outcome, Double newRawMarks, String note) {
        var request = reevaluation(id);
        if (!"pending".equals(request.status())) {
            throw new ConflictException("Re-evaluation " + id + " was already decided (" + request.status() + ")");
        }
        if (authz.rolesOfCurrentUser().stream().noneMatch(EXAM_AUTHORITY_ROLES::contains)) {
            throw new ForbiddenException(
                "Your role cannot decide a re-evaluation (needs one of " + EXAM_AUTHORITY_ROLES + ")");
        }
        if (!List.of("upheld", "revised", "rejected").contains(outcome)) {
            throw new IllegalArgumentException("Outcome must be one of upheld | revised | rejected");
        }

        UUID revisionId = null;
        if ("revised".equals(outcome)) {
            if (newRawMarks == null) {
                throw new IllegalArgumentException("A revised outcome needs the new mark");
            }
            UUID componentId = jdbc.queryForObject(
                "SELECT assessment_component_id FROM mark WHERE id = ?", UUID.class, request.markId());
            UUID schoolId = jdbc.queryForObject("SELECT school_id FROM mark WHERE id = ?", UUID.class, request.markId());
            // A sealed assessment is written through here; a closed year is not.
            academicYears.requireOpenForAssessmentComponent(componentId);
            Component component = component(componentId);
            // Re-evaluation is exactly the case a locked assessment exists for,
            // so it is allowed to write through the lock — but only here, only
            // through an authorised decision, and only leaving a revision row.
            write(schoolId, component,
                new MarkEntry(request.studentId(), newRawMarks, "entered", null, null),
                null, "re_evaluation",
                "Re-evaluation " + id + (note == null || note.isBlank() ? "" : ": " + note));
            revisionId = jdbc.query(
                "SELECT id FROM mark_revision WHERE mark_id = ? ORDER BY revision_no DESC LIMIT 1",
                (rs, i) -> UUID.fromString(rs.getString("id")), request.markId())
                .stream().findFirst().orElse(null);
        }

        jdbc.update(
            "UPDATE mark_reevaluation SET status = ?, decided_by_user_id = ?, decided_at = now(), " +
            "  decision_note = ?, revision_id = ? WHERE id = ?",
            "revised".equals(outcome) ? "revised" : outcome, currentUserId(), note, revisionId, id);

        if ("revised".equals(outcome)) {
            // The card a family is looking at has to catch up with the mark it
            // was built from; a locked one is left alone and reported instead.
            reportCards.refreshDraftCardsFor(request.studentId());
        }
        return reevaluation(id);
    }

    public MarkReevaluationDto reevaluation(UUID id) {
        return jdbc.query(REEVAL_SELECT + " WHERE id = ?", REEVAL_MAPPER, id).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Re-evaluation not found: " + id));
    }

    public List<MarkReevaluationDto> reevaluationsForStudent(UUID studentId) {
        return jdbc.query(REEVAL_SELECT + " WHERE student_id = ? ORDER BY requested_at DESC",
            REEVAL_MAPPER, studentId);
    }

    private static final String REEVAL_SELECT =
        "SELECT id, mark_id, student_id, reason, requested_by_user_id, requested_at, status, " +
        "       decided_by_user_id, decided_at, decision_note, revision_id FROM mark_reevaluation";

    private static final RowMapper<MarkReevaluationDto> REEVAL_MAPPER = (rs, i) -> new MarkReevaluationDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("mark_id")),
        UUID.fromString(rs.getString("student_id")),
        rs.getString("reason"),
        rs.getString("requested_by_user_id") == null ? null : UUID.fromString(rs.getString("requested_by_user_id")),
        rs.getTimestamp("requested_at").toInstant(),
        rs.getString("status"),
        rs.getString("decided_by_user_id") == null ? null : UUID.fromString(rs.getString("decided_by_user_id")),
        rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
        rs.getString("decision_note"),
        rs.getString("revision_id") == null ? null : UUID.fromString(rs.getString("revision_id")));

    // -------------------------------------------------------------- internals

    /** The component and the assessment context every write is checked against. */
    record Component(UUID id, UUID assessmentId, double maxMarks, UUID subjectId, UUID sectionId,
                     String assessmentStatus, LocalDate onDate) {}

    Component component(UUID componentId) {
        var rows = jdbc.query(
            "SELECT ac.id, ac.assessment_id, ac.max_marks, a.subject_id, a.section_id, a.status, " +
            "       COALESCE(a.scheduled_on, CURRENT_DATE) AS on_date " +
            "FROM assessment_component ac JOIN assessment a ON a.id = ac.assessment_id WHERE ac.id = ?",
            (rs, i) -> new Component(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("assessment_id")),
                rs.getDouble("max_marks"),
                UUID.fromString(rs.getString("subject_id")),
                UUID.fromString(rs.getString("section_id")),
                rs.getString("status"),
                rs.getDate("on_date").toLocalDate()),
            componentId);
        if (rows.isEmpty()) throw new NotFoundException("Assessment component not found: " + componentId);
        return rows.get(0);
    }

    private void requireWritable(Component component) {
        if (SEALED.contains(component.assessmentStatus())) {
            throw new ConflictException(
                "Assessment " + component.assessmentId() + " is " + component.assessmentStatus()
                + "; reopen it through /v1/assessment/{id}/status with a reason, or raise a re-evaluation");
        }
    }

    private record Existing(UUID id, Double rawMarks, String status, String gradeLetter) {}

    private Existing existing(UUID componentId, UUID studentId) {
        return jdbc.query(
            "SELECT id, raw_marks, status, grade_letter FROM mark " +
            "WHERE assessment_component_id = ? AND student_id = ?",
            (rs, i) -> new Existing(UUID.fromString(rs.getString("id")), Jdbc.nullableDouble(rs, "raw_marks"),
                rs.getString("status"), rs.getString("grade_letter")),
            componentId, studentId).stream().findFirst().orElse(null);
    }

    private void recordRevision(UUID schoolId, UUID markId, String kind, Existing before,
                                Double newRawMarks, String newStatus, String newGradeLetter, String reason) {
        Integer next = jdbc.queryForObject(
            "SELECT COALESCE(max(revision_no), 0) + 1 FROM mark_revision WHERE mark_id = ?", Integer.class, markId);
        jdbc.update(
            "INSERT INTO mark_revision (id, school_id, mark_id, revision_no, kind, old_raw_marks, old_status, " +
            "  old_grade_letter, new_raw_marks, new_status, new_grade_letter, reason, changed_by_user_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), schoolId, markId, next, kind, before.rawMarks(), before.status(),
            before.gradeLetter(), newRawMarks, newStatus, newGradeLetter, reason, currentUserId());
    }

    private UUID currentUserId() {
        var snap = TenantContext.get();
        return snap == null ? null : snap.userAccountId();
    }

    private void requireGuardianOfOrStaff(UUID studentId) {
        var snap = TenantContext.get();
        if (snap == null) throw new ForbiddenException("No caller");
        if ("staff".equals(snap.subjectType()) || "platform_admin".equals(snap.subjectType())) return;
        if (!"guardian".equals(snap.subjectType())) {
            throw new ForbiddenException("Only a guardian or the school can raise a re-evaluation");
        }
        Integer linked = jdbc.queryForObject(
            "SELECT count(*) FROM guardian_student gs " +
            "JOIN user_account ua ON ua.subject_id = gs.guardian_id AND ua.subject_type = 'guardian' " +
            "WHERE ua.id = ? AND gs.student_id = ?",
            Integer.class, snap.userAccountId(), studentId);
        if (linked == null || linked == 0) {
            throw new ForbiddenException("That student is not linked to your account");
        }
    }
}
