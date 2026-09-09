package com.schoolsoft.assessment.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A student's results across their whole time at the school, for the modules
 * that have to print them — the transcript on a leaving certificate (GRAD-02),
 * and the academic summary a TC carries.
 *
 * <h2>Published cards only</h2>
 * The source is {@code report_card}, and only cards the school actually
 * published. A transcript assembled by re-averaging live marks would disagree
 * with the report card the family already holds — a re-evaluation landing after
 * the card was published is exactly the case where it would — and of the two
 * documents the one already in the family's hands is the one that is right. So
 * the transcript quotes the cards rather than recomputing them, and a term with
 * no published card is absent from it rather than silently averaged in.
 */
@Service
public class AcademicRecord {

    private final DataSource dataSource;

    public AcademicRecord(DataSource dataSource) { this.dataSource = dataSource; }

    /** One published card, flattened to what a certificate prints. */
    public record TermResult(
        UUID academicYearId,
        String academicYearCode,
        UUID termId,
        String termName,
        String gradeCode,
        String sectionCode,
        Double totalMarks,
        Double totalMaxMarks,
        Double overallPct,
        String overallGrade,
        Integer classRank,
        Integer classSize,
        String promotionDecision,
        List<SubjectLine> subjects
    ) {}

    public record SubjectLine(
        String subjectCode,
        String subjectName,
        Double marksObtained,
        Double maxMarks,
        Double percentage,
        String gradeLetter,
        String resultStatus
    ) {}

    /** Every published card the student holds, oldest first. */
    public List<TermResult> forStudent(UUID studentId) {
        var jdbc = new JdbcTemplate(dataSource);
        List<TermResult> cards = jdbc.query(
            "SELECT rc.id, rc.academic_year_id, ay.code AS ay_code, rc.term_id, " +
            "       COALESCE(t.name, 'Final') AS term_name, g.code AS grade_code, sec.code AS section_code, " +
            "       rc.total_marks, rc.total_max_marks, rc.overall_pct, rc.overall_grade, " +
            "       rc.class_rank, rc.class_size, rc.promotion_decision " +
            "FROM report_card rc " +
            "JOIN academic_year ay ON ay.id = rc.academic_year_id " +
            "LEFT JOIN term    t   ON t.id = rc.term_id " +
            "LEFT JOIN section sec ON sec.id = rc.section_id " +
            "LEFT JOIN grade   g   ON g.id = sec.grade_id " +
            "WHERE rc.student_id = ? AND rc.status = 'published' " +
            "ORDER BY ay.starts_on, t.starts_on NULLS LAST",
            (rs, i) -> new TermResult(
                UUID.fromString(rs.getString("academic_year_id")),
                rs.getString("ay_code"),
                rs.getString("term_id") == null ? null : UUID.fromString(rs.getString("term_id")),
                rs.getString("term_name"),
                rs.getString("grade_code"),
                rs.getString("section_code"),
                nullable(rs.getObject("total_marks"), rs.getDouble("total_marks")),
                nullable(rs.getObject("total_max_marks"), rs.getDouble("total_max_marks")),
                nullable(rs.getObject("overall_pct"), rs.getDouble("overall_pct")),
                rs.getString("overall_grade"),
                rs.getObject("class_rank") == null ? null : rs.getInt("class_rank"),
                rs.getObject("class_size") == null ? null : rs.getInt("class_size"),
                rs.getString("promotion_decision"),
                subjects(jdbc, UUID.fromString(rs.getString("id")))),
            studentId);
        return cards;
    }

    /**
     * The most recent published card, which is what a TC quotes as "last class
     * studied and how they did in it". Empty when the child left before any card
     * was published, which is a real case and prints as a blank line rather than
     * as a fabricated one.
     */
    public java.util.Optional<TermResult> latestFor(UUID studentId) {
        List<TermResult> all = forStudent(studentId);
        return all.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(all.get(all.size() - 1));
    }

    /**
     * The window a transcript's attendance line should cover: the student's
     * first day to their last. Returned as a pair so a caller need not
     * re-derive it from the enrolment rows.
     */
    public java.util.Optional<LocalDate[]> attendanceWindow(UUID studentId) {
        var jdbc = new JdbcTemplate(dataSource);
        var rows = jdbc.query(
            "SELECT min(starts_on) AS from_on, max(COALESCE(ends_on, CURRENT_DATE)) AS to_on " +
            "FROM enrolment WHERE student_id = ?",
            (rs, i) -> rs.getDate("from_on") == null ? null
                : new LocalDate[]{rs.getDate("from_on").toLocalDate(), rs.getDate("to_on").toLocalDate()},
            studentId);
        return rows.isEmpty() || rows.get(0) == null
            ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    private static List<SubjectLine> subjects(JdbcTemplate jdbc, UUID cardId) {
        return jdbc.query(
            "SELECT subject_code, subject_name, marks_obtained, max_marks, percentage, grade_letter, " +
            "       result_status FROM report_card_subject WHERE report_card_id = ? ORDER BY sort_order",
            (rs, i) -> new SubjectLine(
                rs.getString("subject_code"), rs.getString("subject_name"),
                nullable(rs.getObject("marks_obtained"), rs.getDouble("marks_obtained")),
                nullable(rs.getObject("max_marks"), rs.getDouble("max_marks")),
                nullable(rs.getObject("percentage"), rs.getDouble("percentage")),
                rs.getString("grade_letter"), rs.getString("result_status")),
            cardId);
    }

    private static Double nullable(Object raw, double value) {
        return raw == null ? null : value;
    }
}
