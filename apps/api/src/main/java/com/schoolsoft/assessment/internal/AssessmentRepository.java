package com.schoolsoft.assessment.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.assessment.api.AssessmentComponentDto;
import com.schoolsoft.assessment.api.AssessmentDto;
import com.schoolsoft.assessment.api.MarkDto;
import com.schoolsoft.assessment.api.ReportCardDto;
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
    private final ObjectMapper json;

    public AssessmentRepository(JdbcTemplate jdbc, ObjectMapper json, AcademicYearGuard academicYears) {
        this.academicYears = academicYears;
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

    public AssessmentDto setStatus(UUID id, String status) {
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

    public ReportCardDto generateReportCard(
        UUID schoolId, UUID studentId, UUID academicYearId, UUID termId,
        String strategyCode, String templateCode, Map<String, Object> payload
    ) {
        UUID id = UUID.randomUUID();
        try {
            PGobject payloadJson = new PGobject();
            payloadJson.setType("jsonb");
            payloadJson.setValue(json.writeValueAsString(payload == null ? Map.of() : payload));
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
}
