package com.schoolsoft.lms.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.lms.api.AssignmentDto;
import com.schoolsoft.lms.api.AssignmentSubmissionDto;
import com.schoolsoft.lms.api.ContentItemDto;
import com.schoolsoft.lms.api.LessonPlanDto;
import com.schoolsoft.lms.api.QuizAttemptDto;
import com.schoolsoft.lms.api.QuizDto;
import com.schoolsoft.lms.api.QuizQuestionDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class LmsRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public LmsRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private PGobject jsonb(Object value) {
        try {
            PGobject o = new PGobject();
            o.setType("jsonb");
            o.setValue(json.writeValueAsString(value == null ? Map.of() : value));
            return o;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // -------------------------- Content --------------------------

    private static final RowMapper<ContentItemDto> CONTENT_MAPPER = (rs, i) -> new ContentItemDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("subject_id") == null ? null : UUID.fromString(rs.getString("subject_id")),
        rs.getString("curriculum_node_id") == null ? null : UUID.fromString(rs.getString("curriculum_node_id")),
        rs.getString("title"),
        rs.getString("visibility"),
        rs.getString("created_by_staff_id") == null ? null : UUID.fromString(rs.getString("created_by_staff_id")),
        rs.getTimestamp("created_at").toInstant()
    );

    private static final String CONTENT_COLS =
        "id, school_id, subject_id, curriculum_node_id, title, visibility, created_by_staff_id, created_at";

    public List<ContentItemDto> listContent(UUID schoolId, UUID subjectId) {
        String sql = "SELECT " + CONTENT_COLS + " FROM content_item WHERE school_id = ?" +
            (subjectId == null ? "" : " AND subject_id = ?") + " ORDER BY created_at DESC";
        return subjectId == null ? jdbc.query(sql, CONTENT_MAPPER, schoolId) : jdbc.query(sql, CONTENT_MAPPER, schoolId, subjectId);
    }

    public ContentItemDto createContent(
        UUID schoolId, UUID subjectId, UUID curriculumNodeId, String title, Object body, String visibility, UUID createdByStaffId
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO content_item (id, school_id, subject_id, curriculum_node_id, title, body, visibility, created_by_staff_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, subjectId, curriculumNodeId, title, jsonb(body), visibility == null ? "school" : visibility, createdByStaffId
        );
        return jdbc.queryForObject("SELECT " + CONTENT_COLS + " FROM content_item WHERE id = ?", CONTENT_MAPPER, id);
    }

    // -------------------------- Lesson Plans --------------------------

    private static final RowMapper<LessonPlanDto> LESSON_PLAN_MAPPER = (rs, i) -> new LessonPlanDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("section_id")),
        UUID.fromString(rs.getString("subject_id")),
        rs.getString("curriculum_node_id") == null ? null : UUID.fromString(rs.getString("curriculum_node_id")),
        rs.getString("title"),
        rs.getDate("planned_for") == null ? null : rs.getDate("planned_for").toLocalDate(),
        (Integer) rs.getObject("duration_minutes"),
        rs.getString("status"),
        rs.getString("created_by_staff_id") == null ? null : UUID.fromString(rs.getString("created_by_staff_id"))
    );

    private static final String LESSON_PLAN_COLS =
        "id, school_id, section_id, subject_id, curriculum_node_id, title, planned_for, duration_minutes, status, created_by_staff_id";

    public List<LessonPlanDto> listLessonPlans(UUID sectionId) {
        return jdbc.query(
            "SELECT " + LESSON_PLAN_COLS + " FROM lesson_plan WHERE section_id = ? ORDER BY planned_for",
            LESSON_PLAN_MAPPER, sectionId
        );
    }

    public LessonPlanDto createLessonPlan(
        UUID schoolId, UUID sectionId, UUID subjectId, UUID curriculumNodeId, String title,
        LocalDate plannedFor, Integer durationMinutes, UUID createdByStaffId
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO lesson_plan (id, school_id, section_id, subject_id, curriculum_node_id, title, planned_for, duration_minutes, created_by_staff_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, sectionId, subjectId, curriculumNodeId, title,
            plannedFor == null ? null : Date.valueOf(plannedFor), durationMinutes, createdByStaffId
        );
        return jdbc.queryForObject("SELECT " + LESSON_PLAN_COLS + " FROM lesson_plan WHERE id = ?", LESSON_PLAN_MAPPER, id);
    }

    public LessonPlanDto setLessonPlanStatus(UUID id, String status) {
        int updated = jdbc.update("UPDATE lesson_plan SET status = ? WHERE id = ?", status, id);
        if (updated == 0) throw new NotFoundException("Lesson plan not found: " + id);
        return jdbc.queryForObject("SELECT " + LESSON_PLAN_COLS + " FROM lesson_plan WHERE id = ?", LESSON_PLAN_MAPPER, id);
    }

    // -------------------------- Assignments --------------------------

    private static final RowMapper<AssignmentDto> ASSIGNMENT_MAPPER = (rs, i) -> new AssignmentDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("section_id")),
        UUID.fromString(rs.getString("subject_id")),
        rs.getString("title"),
        rs.getString("instructions"),
        rs.getString("submission_type"),
        rs.getTimestamp("due_at") == null ? null : rs.getTimestamp("due_at").toInstant(),
        (Double) rs.getObject("max_marks"),
        rs.getString("status"),
        rs.getString("created_by_staff_id") == null ? null : UUID.fromString(rs.getString("created_by_staff_id"))
    );

    private static final String ASSIGNMENT_COLS =
        "id, school_id, section_id, subject_id, title, instructions, submission_type, due_at, max_marks, status, created_by_staff_id";

    public List<AssignmentDto> listAssignments(UUID sectionId) {
        return jdbc.query(
            "SELECT " + ASSIGNMENT_COLS + " FROM assignment WHERE section_id = ? ORDER BY due_at",
            ASSIGNMENT_MAPPER, sectionId
        );
    }

    public AssignmentDto createAssignment(
        UUID schoolId, UUID sectionId, UUID subjectId, String title, String instructions,
        String submissionType, Instant dueAt, Double maxMarks, UUID createdByStaffId
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO assignment (id, school_id, section_id, subject_id, title, instructions, submission_type, due_at, max_marks, created_by_staff_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, sectionId, subjectId, title, instructions,
            submissionType == null ? "file" : submissionType, dueAt == null ? null : Timestamp.from(dueAt), maxMarks, createdByStaffId
        );
        return jdbc.queryForObject("SELECT " + ASSIGNMENT_COLS + " FROM assignment WHERE id = ?", ASSIGNMENT_MAPPER, id);
    }

    private static final RowMapper<AssignmentSubmissionDto> SUBMISSION_MAPPER = (rs, i) -> new AssignmentSubmissionDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("assignment_id")),
        UUID.fromString(rs.getString("student_id")),
        rs.getString("body"),
        rs.getTimestamp("submitted_at").toInstant(),
        (Double) rs.getObject("marks"),
        rs.getString("feedback"),
        rs.getTimestamp("graded_at") == null ? null : rs.getTimestamp("graded_at").toInstant()
    );

    private static final String SUBMISSION_COLS =
        "id, assignment_id, student_id, body, submitted_at, marks, feedback, graded_at";

    public List<AssignmentSubmissionDto> listSubmissions(UUID assignmentId) {
        return jdbc.query(
            "SELECT " + SUBMISSION_COLS + " FROM assignment_submission WHERE assignment_id = ? ORDER BY submitted_at",
            SUBMISSION_MAPPER, assignmentId
        );
    }

    public AssignmentSubmissionDto submit(UUID assignmentId, UUID studentId, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO assignment_submission (id, assignment_id, student_id, body) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (assignment_id, student_id) DO UPDATE SET body = EXCLUDED.body, submitted_at = now()",
            id, assignmentId, studentId, body
        );
        return jdbc.queryForObject(
            "SELECT " + SUBMISSION_COLS + " FROM assignment_submission WHERE assignment_id = ? AND student_id = ?",
            SUBMISSION_MAPPER, assignmentId, studentId
        );
    }

    public AssignmentSubmissionDto grade(UUID submissionId, double marks, String feedback, UUID gradedByStaffId) {
        int updated = jdbc.update(
            "UPDATE assignment_submission SET marks = ?, feedback = ?, graded_by_staff_id = ?, graded_at = now() WHERE id = ?",
            marks, feedback, gradedByStaffId, submissionId
        );
        if (updated == 0) throw new NotFoundException("Submission not found: " + submissionId);
        return jdbc.queryForObject("SELECT " + SUBMISSION_COLS + " FROM assignment_submission WHERE id = ?", SUBMISSION_MAPPER, submissionId);
    }

    // -------------------------- Quiz --------------------------

    private static final RowMapper<QuizDto> QUIZ_MAPPER = (rs, i) -> new QuizDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("subject_id") == null ? null : UUID.fromString(rs.getString("subject_id")),
        rs.getString("title"),
        (Integer) rs.getObject("duration_minutes"),
        rs.getBoolean("randomise"),
        rs.getBoolean("lockdown")
    );

    private static final String QUIZ_COLS = "id, school_id, subject_id, title, duration_minutes, randomise, lockdown";

    public List<QuizDto> listQuizzes(UUID schoolId) {
        return jdbc.query("SELECT " + QUIZ_COLS + " FROM quiz WHERE school_id = ? ORDER BY created_at DESC", QUIZ_MAPPER, schoolId);
    }

    public QuizDto createQuiz(
        UUID schoolId, UUID subjectId, String title, Integer durationMinutes, boolean randomise, boolean lockdown, UUID createdByStaffId
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO quiz (id, school_id, subject_id, title, duration_minutes, randomise, lockdown, created_by_staff_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, subjectId, title, durationMinutes, randomise, lockdown, createdByStaffId
        );
        return jdbc.queryForObject("SELECT " + QUIZ_COLS + " FROM quiz WHERE id = ?", QUIZ_MAPPER, id);
    }

    private static final RowMapper<QuizQuestionDto> QUESTION_MAPPER = (rs, i) -> new QuizQuestionDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("quiz_id")),
        rs.getString("curriculum_node_id") == null ? null : UUID.fromString(rs.getString("curriculum_node_id")),
        rs.getString("kind"),
        rs.getString("prompt"),
        rs.getDouble("marks"),
        rs.getInt("sort_order")
    );

    public List<QuizQuestionDto> listQuestions(UUID quizId) {
        return jdbc.query(
            "SELECT id, quiz_id, curriculum_node_id, kind, prompt, marks, sort_order FROM quiz_question " +
            "WHERE quiz_id = ? ORDER BY sort_order",
            QUESTION_MAPPER, quizId
        );
    }

    public QuizQuestionDto addQuestion(
        UUID quizId, UUID curriculumNodeId, String kind, String prompt, Object options, Object answer, double marks, int sortOrder
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO quiz_question (id, quiz_id, curriculum_node_id, kind, prompt, options, answer, marks, sort_order) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, quizId, curriculumNodeId, kind, prompt, jsonb(options), jsonb(answer), marks, sortOrder
        );
        return jdbc.queryForObject(
            "SELECT id, quiz_id, curriculum_node_id, kind, prompt, marks, sort_order FROM quiz_question WHERE id = ?",
            QUESTION_MAPPER, id
        );
    }

    private static final RowMapper<QuizAttemptDto> ATTEMPT_MAPPER = (rs, i) -> new QuizAttemptDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("quiz_id")),
        UUID.fromString(rs.getString("student_id")),
        rs.getTimestamp("started_at").toInstant(),
        rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant(),
        (Double) rs.getObject("score")
    );

    public QuizAttemptDto startAttempt(UUID quizId, UUID studentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO quiz_attempt (id, quiz_id, student_id) VALUES (?, ?, ?)", id, quizId, studentId);
        return jdbc.queryForObject(
            "SELECT id, quiz_id, student_id, started_at, submitted_at, score FROM quiz_attempt WHERE id = ?",
            ATTEMPT_MAPPER, id
        );
    }

    public QuizAttemptDto submitAttempt(UUID attemptId, Object responses, double score) {
        int updated = jdbc.update(
            "UPDATE quiz_attempt SET submitted_at = now(), responses = ?, score = ? WHERE id = ?",
            jsonb(responses), score, attemptId
        );
        if (updated == 0) throw new NotFoundException("Quiz attempt not found: " + attemptId);
        return jdbc.queryForObject(
            "SELECT id, quiz_id, student_id, started_at, submitted_at, score FROM quiz_attempt WHERE id = ?",
            ATTEMPT_MAPPER, attemptId
        );
    }

    public List<QuizAttemptDto> listAttempts(UUID quizId) {
        return jdbc.query(
            "SELECT id, quiz_id, student_id, started_at, submitted_at, score FROM quiz_attempt WHERE quiz_id = ? ORDER BY started_at",
            ATTEMPT_MAPPER, quizId
        );
    }
}
