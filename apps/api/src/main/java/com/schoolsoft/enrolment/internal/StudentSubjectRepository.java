package com.schoolsoft.enrolment.internal;

import com.schoolsoft.enrolment.api.StudentSubjectDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the elections behind
 * {@link com.schoolsoft.enrolment.api.SubjectSetResolver}. The resolver is the
 * only thing other modules talk to; this holds the SQL.
 */
@Repository
public class StudentSubjectRepository {

    private final JdbcTemplate jdbc;

    public StudentSubjectRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<StudentSubjectDto> MAPPER = (rs, i) -> new StudentSubjectDto(
        rs.getString("id") == null ? null : UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("enrolment_id")),
        UUID.fromString(rs.getString("student_id")),
        UUID.fromString(rs.getString("subject_id")),
        rs.getString("subject_code"),
        rs.getString("subject_name"),
        rs.getString("origin"),
        rs.getString("elective_group_id") == null ? null : UUID.fromString(rs.getString("elective_group_id")),
        rs.getString("elective_group_code"),
        rs.getString("status"),
        rs.getDate("effective_from") == null ? null : rs.getDate("effective_from").toLocalDate(),
        rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate()
    );

    /**
     * The resolution rule itself, as one query: the section's compulsory
     * subjects, plus this enrolment's live elections. Written as a UNION rather
     * than two calls so callers cannot accidentally use half of it.
     */
    private static final String RESOLVE_SQL =
        "SELECT NULL::text AS id, e.id::text AS enrolment_id, e.student_id::text AS student_id, " +
        "       sub.id::text AS subject_id, sub.code AS subject_code, sub.name AS subject_name, " +
        "       'compulsory' AS origin, NULL::text AS elective_group_id, NULL::text AS elective_group_code, " +
        "       'elected' AS status, e.starts_on AS effective_from, e.ends_on AS effective_to " +
        "FROM enrolment e " +
        "JOIN section_subject_teacher sst ON sst.section_id = e.section_id AND NOT sst.is_elective " +
        "JOIN subject sub ON sub.id = sst.subject_id " +
        "WHERE e.id = ? " +
        "UNION " +
        "SELECT ss.id::text, ss.enrolment_id::text, e.student_id::text, sub.id::text, sub.code, sub.name, " +
        "       'elective', ss.elective_group_id::text, eg.code, ss.status, ss.effective_from, ss.effective_to " +
        "FROM student_subject ss " +
        "JOIN enrolment e ON e.id = ss.enrolment_id " +
        "JOIN subject sub ON sub.id = ss.subject_id " +
        "LEFT JOIN elective_group eg ON eg.id = ss.elective_group_id " +
        "WHERE ss.enrolment_id = ? AND ss.status = 'elected' " +
        "  AND (? BETWEEN ss.effective_from AND COALESCE(ss.effective_to, 'infinity'::date)) " +
        "ORDER BY subject_code";

    public List<StudentSubjectDto> resolveForEnrolment(UUID enrolmentId, LocalDate onDate) {
        return jdbc.query(RESOLVE_SQL, MAPPER, enrolmentId, enrolmentId, Date.valueOf(onDate));
    }

    /** The enrolment a student holds on a date — elections hang off enrolments, not students. */
    public UUID enrolmentIdFor(UUID studentId, LocalDate onDate) {
        var rows = jdbc.query(
            "SELECT id FROM enrolment WHERE student_id = ? " +
            "  AND starts_on <= ? AND COALESCE(ends_on, 'infinity'::date) >= ? " +
            "ORDER BY (status = 'active') DESC, starts_on DESC LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString("id")),
            studentId, Date.valueOf(onDate), Date.valueOf(onDate));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<StudentSubjectDto> listElections(UUID enrolmentId) {
        return jdbc.query(
            "SELECT ss.id::text AS id, ss.enrolment_id::text AS enrolment_id, e.student_id::text AS student_id, " +
            "       sub.id::text AS subject_id, sub.code AS subject_code, sub.name AS subject_name, " +
            "       'elective' AS origin, ss.elective_group_id::text AS elective_group_id, " +
            "       eg.code AS elective_group_code, ss.status, ss.effective_from, ss.effective_to " +
            "FROM student_subject ss " +
            "JOIN enrolment e ON e.id = ss.enrolment_id " +
            "JOIN subject sub ON sub.id = ss.subject_id " +
            "LEFT JOIN elective_group eg ON eg.id = ss.elective_group_id " +
            "WHERE ss.enrolment_id = ? ORDER BY sub.code",
            MAPPER, enrolmentId);
    }

    /**
     * Records an election, validating it against its option block: the subject
     * has to be one of the group's options, and the student may not exceed the
     * group's pick count. Both are the mistakes a bulk option-entry screen
     * actually makes.
     */
    public StudentSubjectDto elect(UUID enrolmentId, UUID subjectId, UUID electiveGroupId, LocalDate effectiveFrom) {
        var enrolment = jdbc.query(
            "SELECT e.school_id, e.section_id, s.grade_id, e.academic_year_id " +
            "FROM enrolment e JOIN section s ON s.id = e.section_id WHERE e.id = ?",
            (rs, i) -> new UUID[]{
                UUID.fromString(rs.getString("school_id")),
                UUID.fromString(rs.getString("section_id")),
                UUID.fromString(rs.getString("grade_id")),
                UUID.fromString(rs.getString("academic_year_id"))
            }, enrolmentId);
        if (enrolment.isEmpty()) throw new NotFoundException("Enrolment not found: " + enrolmentId);
        UUID schoolId = enrolment.get(0)[0];
        UUID gradeId = enrolment.get(0)[2];
        UUID academicYearId = enrolment.get(0)[3];

        if (electiveGroupId != null) {
            var group = jdbc.query(
                "SELECT code, grade_id, academic_year_id, min_picks, max_picks FROM elective_group WHERE id = ?",
                (rs, i) -> new Object[]{
                    rs.getString("code"),
                    UUID.fromString(rs.getString("grade_id")),
                    UUID.fromString(rs.getString("academic_year_id")),
                    rs.getInt("min_picks"), rs.getInt("max_picks")
                }, electiveGroupId);
            if (group.isEmpty()) throw new NotFoundException("Elective group not found: " + electiveGroupId);
            String code = (String) group.get(0)[0];
            if (!gradeId.equals(group.get(0)[1]) || !academicYearId.equals(group.get(0)[2])) {
                throw new IllegalArgumentException(
                    "Elective group " + code + " belongs to a different grade or academic year");
            }
            Integer isOption = jdbc.queryForObject(
                "SELECT count(*) FROM elective_group_option WHERE elective_group_id = ? AND subject_id = ?",
                Integer.class, electiveGroupId, subjectId);
            if (isOption == null || isOption == 0) {
                throw new IllegalArgumentException("Subject is not an option of elective group " + code);
            }
            int maxPicks = (Integer) group.get(0)[4];
            Integer alreadyPicked = jdbc.queryForObject(
                "SELECT count(*) FROM student_subject WHERE enrolment_id = ? AND elective_group_id = ? " +
                "  AND status = 'elected' AND subject_id <> ?",
                Integer.class, enrolmentId, electiveGroupId, subjectId);
            if (alreadyPicked != null && alreadyPicked >= maxPicks) {
                throw new IllegalArgumentException(
                    "Elective group " + code + " allows " + maxPicks + " subject(s); the student already has "
                    + alreadyPicked);
            }
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO student_subject (id, school_id, enrolment_id, subject_id, elective_group_id, " +
            "  status, effective_from) VALUES (?, ?, ?, ?, ?, 'elected', ?) " +
            "ON CONFLICT (enrolment_id, subject_id) WHERE status = 'elected' DO UPDATE SET " +
            "  elective_group_id = EXCLUDED.elective_group_id, effective_from = EXCLUDED.effective_from, " +
            "  effective_to = NULL",
            id, schoolId, enrolmentId, subjectId, electiveGroupId, Date.valueOf(effectiveFrom));

        return listElections(enrolmentId).stream()
            .filter(s -> s.subjectId().equals(subjectId) && "elected".equals(s.status()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Election not stored for subject " + subjectId));
    }

    /**
     * Ends an election from a date rather than deleting it: the marks earned
     * while the student took the subject stay explicable.
     */
    public void drop(UUID enrolmentId, UUID subjectId, LocalDate effectiveTo) {
        int updated = jdbc.update(
            "UPDATE student_subject SET status = 'dropped', effective_to = ? " +
            "WHERE enrolment_id = ? AND subject_id = ? AND status = 'elected'",
            Date.valueOf(effectiveTo), enrolmentId, subjectId);
        if (updated == 0) {
            throw new NotFoundException("No live election of subject " + subjectId + " on enrolment " + enrolmentId);
        }
    }

    /** Elective groups a grade offers in an academic year, with their options. */
    public List<UUID> unmetGroups(UUID enrolmentId) {
        return jdbc.query(
            "SELECT eg.id FROM elective_group eg " +
            "JOIN enrolment e ON e.academic_year_id = eg.academic_year_id " +
            "JOIN section s ON s.id = e.section_id AND s.grade_id = eg.grade_id " +
            "WHERE e.id = ? " +
            "  AND (SELECT count(*) FROM student_subject ss " +
            "       WHERE ss.enrolment_id = e.id AND ss.elective_group_id = eg.id AND ss.status = 'elected') " +
            "      < eg.min_picks",
            (rs, i) -> UUID.fromString(rs.getString("id")), enrolmentId);
    }
}
