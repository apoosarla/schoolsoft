package com.schoolsoft.assessment.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.assessment.api.AssessmentComponentDto;
import com.schoolsoft.assessment.api.AssessmentDto;
import com.schoolsoft.assessment.api.MarkDto;
import com.schoolsoft.assessment.api.ReportCardDto;
import com.schoolsoft.enrolment.api.StudentSubjectDto;
import com.schoolsoft.enrolment.api.SubjectSetResolver;
import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.AcademicYearGuard;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AssessmentRepository {

    private final JdbcTemplate jdbc;
    private final AcademicYearGuard academicYears;
    private final SubjectSetResolver subjectSets;
    private final Authz authz;
    private final ObjectMapper json;

    public AssessmentRepository(JdbcTemplate jdbc, ObjectMapper json, AcademicYearGuard academicYears,
                               SubjectSetResolver subjectSets, Authz authz) {
        this.academicYears = academicYears;
        this.subjectSets = subjectSets;
        this.authz = authz;
        this.jdbc = jdbc;
        this.json = json;
    }

    // -------------------------- Assessment --------------------------

    private static final RowMapper<AssessmentDto> ASSESSMENT_MAPPER = (rs, i) -> new AssessmentDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("section_id")),
        UUID.fromString(rs.getString("subject_id")),
        rs.getString("term_id") == null ? null : UUID.fromString(rs.getString("term_id")),
        rs.getString("strategy_code"),
        rs.getString("name"),
        rs.getString("assessment_type"),
        com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "max_marks"),
        com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "weight_pct"),
        rs.getDate("scheduled_on") == null ? null : rs.getDate("scheduled_on").toLocalDate(),
        rs.getString("status")
    );

    private static final String ASSESSMENT_COLS =
        "id, school_id, section_id, subject_id, term_id, strategy_code, name, assessment_type, " +
        "max_marks, weight_pct, scheduled_on, status";

    public List<AssessmentDto> listBySection(UUID sectionId) {
        return jdbc.query(
            "SELECT " + ASSESSMENT_COLS + " FROM assessment WHERE section_id = ? ORDER BY scheduled_on NULLS LAST",
            ASSESSMENT_MAPPER, sectionId
        );
    }

    public Optional<AssessmentDto> find(UUID id) {
        var rows = jdbc.query("SELECT " + ASSESSMENT_COLS + " FROM assessment WHERE id = ?", ASSESSMENT_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public AssessmentDto create(
        UUID schoolId, UUID sectionId, UUID subjectId, UUID termId, String strategyCode,
        String name, String assessmentType, Double maxMarks, Double weightPct, LocalDate scheduledOn
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO assessment (id, school_id, section_id, subject_id, term_id, strategy_code, name, " +
            "  assessment_type, max_marks, weight_pct, scheduled_on) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, sectionId, subjectId, termId, strategyCode, name, assessmentType,
            maxMarks, weightPct, scheduledOn == null ? null : Date.valueOf(scheduledOn)
        );
        return find(id).orElseThrow();
    }

    /** Statuses past which marks are considered final, and reopening one is a decision. */
    private static final List<String> SEALED = List.of("locked", "published");

    /** Roles allowed to reopen a sealed assessment. */
    private static final List<String> UNLOCK_ROLES =
        List.of("principal", "vice_principal", "exams_officer", "academic_coordinator", "it_admin");

    public AssessmentDto setStatus(UUID id, String status, String reason) {
        String current = find(id)
            .orElseThrow(() -> new NotFoundException("Assessment not found: " + id))
            .status();

        // Unlocking is the high-risk direction: marks a parent has already seen
        // become editable again, so it needs an authorised role and a reason,
        // and the audit entry at the endpoint records both (SEC-08).
        if (SEALED.contains(current) && !SEALED.contains(status)) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                    "Reopening a " + current + " assessment needs a reason");
            }
            if (authz.rolesOfCurrentUser().stream().noneMatch(UNLOCK_ROLES::contains)) {
                throw new ForbiddenException(
                    "Your role cannot reopen a " + current + " assessment (needs one of " + UNLOCK_ROLES + ")");
            }
        }

        int updated = jdbc.update("UPDATE assessment SET status = ? WHERE id = ?", status, id);
        if (updated == 0) throw new NotFoundException("Assessment not found: " + id);
        return find(id).orElseThrow();
    }

    // -------------------------- Assessment Component --------------------------

    private static final RowMapper<AssessmentComponentDto> COMPONENT_MAPPER = (rs, i) -> new AssessmentComponentDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("assessment_id")),
        rs.getString("code"),
        rs.getString("name"),
        rs.getDouble("max_marks"),
        com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "weight_pct"),
        rs.getInt("sort_order")
    );

    public List<AssessmentComponentDto> listComponents(UUID assessmentId) {
        return jdbc.query(
            "SELECT id, assessment_id, code, name, max_marks, weight_pct, sort_order FROM assessment_component " +
            "WHERE assessment_id = ? ORDER BY sort_order",
            COMPONENT_MAPPER, assessmentId
        );
    }

    public AssessmentComponentDto addComponent(
        UUID assessmentId, String code, String name, double maxMarks, Double weightPct, int sortOrder
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO assessment_component (id, assessment_id, code, name, max_marks, weight_pct, sort_order) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, assessmentId, code, name, maxMarks, weightPct, sortOrder
        );
        return jdbc.queryForObject(
            "SELECT id, assessment_id, code, name, max_marks, weight_pct, sort_order FROM assessment_component WHERE id = ?",
            COMPONENT_MAPPER, id
        );
    }

    // -------------------------- Marks --------------------------

    private static final RowMapper<MarkDto> MARK_MAPPER = (rs, i) -> new MarkDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("assessment_component_id")),
        UUID.fromString(rs.getString("student_id")),
        com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "raw_marks"),
        rs.getString("grade_letter"),
        rs.getString("remarks"),
        rs.getBoolean("is_absent")
    );

    private static final String MARK_COLS =
        "id, assessment_component_id, student_id, raw_marks, grade_letter, remarks, is_absent";

    public List<MarkDto> listMarks(UUID assessmentComponentId) {
        return jdbc.query(
            "SELECT " + MARK_COLS + " FROM mark WHERE assessment_component_id = ? ORDER BY student_id",
            MARK_MAPPER, assessmentComponentId
        );
    }

    /** Marks belong to a year; a closed year refuses them (GAP-14). */
    public MarkDto enterMark(
        UUID schoolId, UUID assessmentComponentId, UUID studentId, Double rawMarks,
        String gradeLetter, String remarks, boolean isAbsent, UUID enteredByStaffId
    ) {
        academicYears.requireOpenForAssessmentComponent(assessmentComponentId);

        // A mark only means something if the student takes the subject. With
        // electives, the section no longer answers that — the student's own
        // subject set does (GAP-05).
        var subjectAndDate = jdbc.query(
            "SELECT a.subject_id, COALESCE(a.scheduled_on, CURRENT_DATE) AS on_date " +
            "FROM assessment a JOIN assessment_component ac ON ac.assessment_id = a.id WHERE ac.id = ?",
            (rs, i) -> new Object[]{ UUID.fromString(rs.getString("subject_id")),
                                     rs.getDate("on_date").toLocalDate() },
            assessmentComponentId);
        if (subjectAndDate.isEmpty()) {
            throw new NotFoundException("Assessment component not found: " + assessmentComponentId);
        }
        subjectSets.requireStudies(studentId, (UUID) subjectAndDate.get(0)[0],
            (LocalDate) subjectAndDate.get(0)[1]);

        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO mark (id, school_id, assessment_component_id, student_id, raw_marks, grade_letter, remarks, is_absent, entered_by_staff_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (assessment_component_id, student_id) DO UPDATE SET " +
            "  raw_marks = EXCLUDED.raw_marks, grade_letter = EXCLUDED.grade_letter, remarks = EXCLUDED.remarks, " +
            "  is_absent = EXCLUDED.is_absent, entered_by_staff_id = EXCLUDED.entered_by_staff_id, entered_at = now()",
            id, schoolId, assessmentComponentId, studentId, rawMarks, gradeLetter, remarks, isAbsent, enteredByStaffId
        );
        return jdbc.queryForObject(
            "SELECT " + MARK_COLS + " FROM mark WHERE assessment_component_id = ? AND student_id = ?",
            MARK_MAPPER, assessmentComponentId, studentId
        );
    }

    // -------------------------- Report Cards --------------------------

    private static final RowMapper<ReportCardDto> REPORT_CARD_MAPPER = (rs, i) -> new ReportCardDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("student_id")),
        UUID.fromString(rs.getString("academic_year_id")),
        rs.getString("term_id") == null ? null : UUID.fromString(rs.getString("term_id")),
        rs.getString("strategy_code"),
        rs.getString("template_code"),
        rs.getBoolean("is_locked"),
        rs.getTimestamp("generated_at").toInstant()
    );

    private static final String REPORT_CARD_COLS =
        "id, school_id, student_id, academic_year_id, term_id, strategy_code, template_code, is_locked, generated_at";

    public List<ReportCardDto> listReportCards(UUID studentId) {
        return jdbc.query(
            "SELECT " + REPORT_CARD_COLS + " FROM report_card WHERE student_id = ? ORDER BY generated_at DESC",
            REPORT_CARD_MAPPER, studentId
        );
    }

    /**
     * The payload carries the student's own subject set, not their section's
     * (ASMT-13). Two students in one section with different option blocks get
     * different report cards, which is the whole point of the election model.
     */
    public ReportCardDto generateReportCard(
        UUID schoolId, UUID studentId, UUID academicYearId, UUID termId,
        String strategyCode, String templateCode, Map<String, Object> payload
    ) {
        UUID id = UUID.randomUUID();
        try {
            var enriched = new java.util.LinkedHashMap<String, Object>(payload == null ? Map.of() : payload);
            enriched.put("subjects", subjectsForReportCard(studentId, academicYearId, termId));

            PGobject payloadJson = new PGobject();
            payloadJson.setType("jsonb");
            payloadJson.setValue(json.writeValueAsString(enriched));
            jdbc.update(
                "INSERT INTO report_card (id, school_id, student_id, academic_year_id, term_id, strategy_code, template_code, payload) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, schoolId, studentId, academicYearId, termId, strategyCode, templateCode, payloadJson
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return jdbc.queryForObject("SELECT " + REPORT_CARD_COLS + " FROM report_card WHERE id = ?", REPORT_CARD_MAPPER, id);
    }

    public ReportCardDto lockReportCard(UUID id) {
        int updated = jdbc.update("UPDATE report_card SET is_locked = TRUE WHERE id = ?", id);
        if (updated == 0) throw new NotFoundException("Report card not found: " + id);
        return jdbc.queryForObject("SELECT " + REPORT_CARD_COLS + " FROM report_card WHERE id = ?", REPORT_CARD_MAPPER, id);
    }

    /**
     * Unlocking a report card the family has already been shown. Same rule as
     * reopening an assessment: an authorised role, a reason, and an audit row.
     */
    public ReportCardDto unlockReportCard(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Unlocking a report card needs a reason");
        }
        if (authz.rolesOfCurrentUser().stream().noneMatch(UNLOCK_ROLES::contains)) {
            throw new ForbiddenException(
                "Your role cannot unlock a report card (needs one of " + UNLOCK_ROLES + ")");
        }
        int updated = jdbc.update("UPDATE report_card SET is_locked = FALSE WHERE id = ?", id);
        if (updated == 0) throw new NotFoundException("Report card not found: " + id);
        return jdbc.queryForObject("SELECT " + REPORT_CARD_COLS + " FROM report_card WHERE id = ?", REPORT_CARD_MAPPER, id);
    }

    /**
     * Resolved as of the term's end (or the year's), so a card issued after a
     * mid-year option change still lists the subjects that term was taught in.
     */
    private List<Map<String, Object>> subjectsForReportCard(UUID studentId, UUID academicYearId, UUID termId) {
        LocalDate asOf = null;
        if (termId != null) {
            var dates = jdbc.query("SELECT ends_on FROM term WHERE id = ?",
                (rs, i) -> rs.getDate("ends_on").toLocalDate(), termId);
            if (!dates.isEmpty()) asOf = dates.get(0);
        }
        if (asOf == null && academicYearId != null) {
            var dates = jdbc.query("SELECT ends_on FROM academic_year WHERE id = ?",
                (rs, i) -> rs.getDate("ends_on").toLocalDate(), academicYearId);
            if (!dates.isEmpty()) asOf = dates.get(0);
        }
        LocalDate today = LocalDate.now();
        if (asOf == null || asOf.isAfter(today)) asOf = today;

        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (StudentSubjectDto subject : subjectSets.forStudent(studentId, asOf)) {
            rows.add(Map.of(
                "subjectId", subject.subjectId().toString(),
                "code", subject.subjectCode(),
                "name", subject.subjectName(),
                "origin", subject.origin()
            ));
        }
        return rows;
    }
}
