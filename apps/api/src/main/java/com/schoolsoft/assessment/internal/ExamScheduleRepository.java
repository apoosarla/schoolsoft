package com.schoolsoft.assessment.internal;

import com.schoolsoft.assessment.api.ExamScheduleDto;
import com.schoolsoft.assessment.api.ExamSessionDto;
import com.schoolsoft.assessment.api.HallTicketDto;
import com.schoolsoft.enrolment.api.SubjectSetResolver;
import com.schoolsoft.platform.db.Jdbc;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exam operations (ASMT-09).
 *
 * The clash that matters is not the one a section timetable can see. Two
 * papers can be scheduled against a grade without any section clashing, while
 * one child who took both options is booked into two rooms at the same hour —
 * so detection runs over each student's own subject set, and a schedule cannot
 * be published while one exists.
 */
@Repository
public class ExamScheduleRepository {

    private final JdbcTemplate jdbc;
    private final SubjectSetResolver subjectSets;

    public ExamScheduleRepository(JdbcTemplate jdbc, SubjectSetResolver subjectSets) {
        this.jdbc = jdbc;
        this.subjectSets = subjectSets;
    }

    // -------------------------------------------------------------- schedules

    private static final String SCHEDULE_COLS =
        "es.id, es.school_id, es.academic_year_id, es.term_id, es.code, es.name, es.starts_on, es.ends_on, " +
        "es.status, es.published_at, " +
        "(SELECT count(*) FROM exam_session s WHERE s.exam_schedule_id = es.id) AS session_count";

    private static final RowMapper<ExamScheduleDto> SCHEDULE_MAPPER = (rs, i) -> new ExamScheduleDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("academic_year_id")),
        rs.getString("term_id") == null ? null : UUID.fromString(rs.getString("term_id")),
        rs.getString("code"),
        rs.getString("name"),
        rs.getDate("starts_on").toLocalDate(),
        rs.getDate("ends_on").toLocalDate(),
        rs.getString("status"),
        rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
        rs.getInt("session_count"));

    public ExamScheduleDto createSchedule(UUID schoolId, UUID academicYearId, UUID termId, String code,
                                          String name, LocalDate startsOn, LocalDate endsOn) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO exam_schedule (id, school_id, academic_year_id, term_id, code, name, starts_on, ends_on) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, academicYearId, termId, code, name, Date.valueOf(startsOn), Date.valueOf(endsOn));
        return schedule(id);
    }

    public ExamScheduleDto schedule(UUID id) {
        return jdbc.query("SELECT " + SCHEDULE_COLS + " FROM exam_schedule es WHERE es.id = ?", SCHEDULE_MAPPER, id)
            .stream().findFirst().orElseThrow(() -> new NotFoundException("Exam schedule not found: " + id));
    }

    public List<ExamScheduleDto> schedules(UUID schoolId, UUID academicYearId) {
        if (academicYearId == null) {
            return jdbc.query("SELECT " + SCHEDULE_COLS + " FROM exam_schedule es WHERE es.school_id = ? " +
                "ORDER BY es.starts_on DESC", SCHEDULE_MAPPER, schoolId);
        }
        return jdbc.query("SELECT " + SCHEDULE_COLS + " FROM exam_schedule es " +
            "WHERE es.school_id = ? AND es.academic_year_id = ? ORDER BY es.starts_on DESC",
            SCHEDULE_MAPPER, schoolId, academicYearId);
    }

    /**
     * Publication is the gate. A draft schedule may hold clashes while the
     * exams officer works; a published one may not, because from that moment
     * hall tickets and the suppressed class timetable are built from it.
     */
    @Transactional
    public ExamScheduleDto publish(UUID id) {
        ExamScheduleDto schedule = schedule(id);
        List<Clash> clashes = clashes(id);
        if (!clashes.isEmpty()) {
            throw new ConflictException(
                "Cannot publish " + schedule.code() + ": " + clashes.size()
                + " student paper clash(es) — " + clashes.get(0).describe());
        }
        jdbc.update("UPDATE exam_schedule SET status = 'published', published_at = now() WHERE id = ?", id);
        return schedule(id);
    }

    // --------------------------------------------------------------- sessions

    private static final String SESSION_COLS =
        "s.id, s.exam_schedule_id, s.school_id, s.grade_id, s.subject_id, sub.code AS subject_code, " +
        "sub.name AS subject_name, s.paper_code, s.name, s.on_date, s.starts_at, s.ends_at, s.room, " +
        "s.invigilator_staff_id, s.max_marks, s.assessment_id";

    private static final RowMapper<ExamSessionDto> SESSION_MAPPER = (rs, i) -> new ExamSessionDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("exam_schedule_id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("grade_id")),
        UUID.fromString(rs.getString("subject_id")),
        rs.getString("subject_code"),
        rs.getString("subject_name"),
        rs.getString("paper_code"),
        rs.getString("name"),
        rs.getDate("on_date").toLocalDate(),
        rs.getTime("starts_at").toLocalTime(),
        rs.getTime("ends_at").toLocalTime(),
        rs.getString("room"),
        rs.getString("invigilator_staff_id") == null ? null : UUID.fromString(rs.getString("invigilator_staff_id")),
        Jdbc.nullableDouble(rs, "max_marks"),
        rs.getString("assessment_id") == null ? null : UUID.fromString(rs.getString("assessment_id")));

    public ExamSessionDto addSession(UUID scheduleId, UUID gradeId, UUID subjectId, String paperCode, String name,
                                     LocalDate onDate, LocalTime startsAt, LocalTime endsAt, String room,
                                     UUID invigilatorStaffId, Double maxMarks, UUID assessmentId) {
        ExamScheduleDto schedule = schedule(scheduleId);
        if ("published".equals(schedule.status())) {
            throw new ConflictException(
                "Exam schedule " + schedule.code() + " is published; unpublish it before adding papers");
        }
        if (onDate.isBefore(schedule.startsOn()) || onDate.isAfter(schedule.endsOn())) {
            throw new IllegalArgumentException(
                "Paper on " + onDate + " falls outside the exam window " + schedule.startsOn()
                + " .. " + schedule.endsOn());
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO exam_session (id, exam_schedule_id, school_id, grade_id, subject_id, paper_code, name, " +
            "  on_date, starts_at, ends_at, room, invigilator_staff_id, max_marks, assessment_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, scheduleId, schedule.schoolId(), gradeId, subjectId, paperCode == null ? "P1" : paperCode, name,
            Date.valueOf(onDate), Time.valueOf(startsAt), Time.valueOf(endsAt), room, invigilatorStaffId,
            maxMarks, assessmentId);
        return session(id);
    }

    public ExamSessionDto session(UUID id) {
        return jdbc.query("SELECT " + SESSION_COLS + " FROM exam_session s " +
            "JOIN subject sub ON sub.id = s.subject_id WHERE s.id = ?", SESSION_MAPPER, id)
            .stream().findFirst().orElseThrow(() -> new NotFoundException("Exam session not found: " + id));
    }

    public List<ExamSessionDto> sessions(UUID scheduleId) {
        return jdbc.query("SELECT " + SESSION_COLS + " FROM exam_session s " +
            "JOIN subject sub ON sub.id = s.subject_id WHERE s.exam_schedule_id = ? " +
            "ORDER BY s.on_date, s.starts_at, sub.code", SESSION_MAPPER, scheduleId);
    }

    /**
     * Removes a paper. This is how a clash is resolved — the exams officer
     * moves the paper by dropping it and re-adding it at another hour — so it
     * is refused once the schedule is published and candidates hold tickets.
     */
    public void deleteSession(UUID id) {
        ExamSessionDto session = session(id);
        ExamScheduleDto schedule = schedule(session.examScheduleId());
        if ("published".equals(schedule.status())) {
            throw new ConflictException(
                "Exam schedule " + schedule.code() + " is published; unpublish it before removing a paper");
        }
        jdbc.update("DELETE FROM exam_session WHERE id = ?", id);
    }

    /** Back to draft, so the timetable stops being suppressed by it. */
    @Transactional
    public ExamScheduleDto unpublish(UUID id) {
        jdbc.update("UPDATE exam_schedule SET status = 'draft', published_at = NULL WHERE id = ?", id);
        return schedule(id);
    }

    /** Published papers a section's grade sits on a date — what suppresses the class timetable (TT-09). */
    public List<ExamSessionDto> publishedSessionsForGradeOn(UUID gradeId, LocalDate date) {
        return jdbc.query("SELECT " + SESSION_COLS + " FROM exam_session s " +
            "JOIN subject sub ON sub.id = s.subject_id " +
            "JOIN exam_schedule es ON es.id = s.exam_schedule_id AND es.status = 'published' " +
            "WHERE s.grade_id = ? AND s.on_date = ? ORDER BY s.starts_at",
            SESSION_MAPPER, gradeId, Date.valueOf(date));
    }

    /** The papers one student sits, filtered to their own subject set. */
    public List<ExamSessionDto> sessionsForStudent(UUID scheduleId, UUID studentId) {
        ExamScheduleDto schedule = schedule(scheduleId);
        Set<UUID> studied = new java.util.LinkedHashSet<>();
        subjectSets.forStudent(studentId, schedule.startsOn()).forEach(s -> studied.add(s.subjectId()));
        return sessions(scheduleId).stream().filter(s -> studied.contains(s.subjectId())).toList();
    }

    // ------------------------------------------------------------- clashes

    /** One student booked into two papers that overlap. */
    public record Clash(UUID studentId, UUID sessionAId, UUID sessionBId, LocalDate onDate,
                        String subjectA, String subjectB) {
        String describe() {
            return "student " + studentId + " sits " + subjectA + " and " + subjectB + " on " + onDate;
        }
    }

    /**
     * Every per-student overlap in a schedule.
     *
     * Overlapping pairs come from the database (there are few); who sits both
     * comes from the subject-set resolver, one query per grade. A clash is
     * reported per student because that is who has to be moved.
     */
    public List<Clash> clashes(UUID scheduleId) {
        ExamScheduleDto schedule = schedule(scheduleId);
        record Pair(UUID gradeId, UUID sessionA, UUID subjectA, String codeA,
                    UUID sessionB, UUID subjectB, String codeB, LocalDate onDate) {}
        List<Pair> pairs = jdbc.query(
            "SELECT s1.grade_id, s1.id AS a_id, s1.subject_id AS a_subject, suba.code AS a_code, " +
            "       s2.id AS b_id, s2.subject_id AS b_subject, subb.code AS b_code, s1.on_date " +
            "FROM exam_session s1 " +
            "JOIN exam_session s2 ON s2.exam_schedule_id = s1.exam_schedule_id AND s2.id > s1.id " +
            "  AND s2.on_date = s1.on_date AND s2.grade_id = s1.grade_id " +
            "  AND s1.starts_at < s2.ends_at AND s2.starts_at < s1.ends_at " +
            "JOIN subject suba ON suba.id = s1.subject_id " +
            "JOIN subject subb ON subb.id = s2.subject_id " +
            "WHERE s1.exam_schedule_id = ? AND s1.subject_id <> s2.subject_id",
            (rs, i) -> new Pair(
                UUID.fromString(rs.getString("grade_id")),
                UUID.fromString(rs.getString("a_id")), UUID.fromString(rs.getString("a_subject")),
                rs.getString("a_code"),
                UUID.fromString(rs.getString("b_id")), UUID.fromString(rs.getString("b_subject")),
                rs.getString("b_code"),
                rs.getDate("on_date").toLocalDate()),
            scheduleId);
        if (pairs.isEmpty()) return List.of();

        Map<UUID, Map<UUID, Set<UUID>>> byGrade = new LinkedHashMap<>();
        List<Clash> clashes = new ArrayList<>();
        for (Pair pair : pairs) {
            var cohort = byGrade.computeIfAbsent(pair.gradeId(), gradeId ->
                subjectSets.subjectsByStudentInGrade(gradeId, schedule.academicYearId(), schedule.startsOn()));
            for (var entry : cohort.entrySet()) {
                if (entry.getValue().contains(pair.subjectA()) && entry.getValue().contains(pair.subjectB())) {
                    clashes.add(new Clash(entry.getKey(), pair.sessionA(), pair.sessionB(), pair.onDate(),
                        pair.codeA(), pair.codeB()));
                }
            }
        }
        return clashes;
    }

    // ----------------------------------------------------------- hall tickets

    /**
     * Issues a ticket to every candidate in the schedule's grades. Re-running
     * is safe and keeps the numbers already handed out — a reissue that
     * renumbers the hall is worse than no reissue at all.
     */
    @Transactional
    public List<HallTicketDto> issueHallTickets(UUID scheduleId) {
        ExamScheduleDto schedule = schedule(scheduleId);
        if (!"published".equals(schedule.status())) {
            throw new ConflictException("Publish the exam schedule before issuing hall tickets");
        }
        List<UUID> grades = jdbc.queryForList(
            "SELECT DISTINCT grade_id FROM exam_session WHERE exam_schedule_id = ?", UUID.class, scheduleId);

        int seq = jdbc.queryForObject(
            "SELECT count(*) FROM exam_hall_ticket WHERE exam_schedule_id = ?", Integer.class, scheduleId);
        for (UUID gradeId : grades) {
            List<UUID> students = jdbc.queryForList(
                "SELECT e.student_id FROM enrolment e " +
                "JOIN section s ON s.id = e.section_id AND s.grade_id = ? AND s.academic_year_id = ? " +
                "WHERE e.status = 'active' " +
                "  AND NOT EXISTS (SELECT 1 FROM exam_hall_ticket t " +
                "                  WHERE t.exam_schedule_id = ? AND t.student_id = e.student_id) " +
                "ORDER BY e.roll_no",
                UUID.class, gradeId, schedule.academicYearId(), scheduleId);
            for (UUID studentId : students) {
                seq++;
                jdbc.update(
                    "INSERT INTO exam_hall_ticket (id, school_id, exam_schedule_id, student_id, ticket_no, seat_no) " +
                    "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (exam_schedule_id, student_id) DO NOTHING",
                    UUID.randomUUID(), schedule.schoolId(), scheduleId, studentId,
                    schedule.code() + "-" + String.format("%04d", seq), String.valueOf(seq));
            }
        }
        return hallTickets(scheduleId);
    }

    public List<HallTicketDto> hallTickets(UUID scheduleId) {
        List<UUID> students = jdbc.queryForList(
            "SELECT student_id FROM exam_hall_ticket WHERE exam_schedule_id = ? ORDER BY ticket_no",
            UUID.class, scheduleId);
        List<HallTicketDto> tickets = new ArrayList<>();
        for (UUID studentId : students) {
            tickets.add(hallTicket(scheduleId, studentId));
        }
        return tickets;
    }

    public HallTicketDto hallTicket(UUID scheduleId, UUID studentId) {
        record Row(UUID id, String ticketNo, String seatNo, java.sql.Timestamp issuedAt,
                   String name, String admissionNo) {}
        Row row = jdbc.query(
            "SELECT t.id, t.ticket_no, t.seat_no, t.issued_at, " +
            "       trim(concat_ws(' ', s.first_name, s.last_name)) AS name, s.admission_no " +
            "FROM exam_hall_ticket t JOIN student s ON s.id = t.student_id " +
            "WHERE t.exam_schedule_id = ? AND t.student_id = ?",
            (rs, i) -> new Row(UUID.fromString(rs.getString("id")), rs.getString("ticket_no"),
                rs.getString("seat_no"), rs.getTimestamp("issued_at"), rs.getString("name"),
                rs.getString("admission_no")),
            scheduleId, studentId).stream().findFirst().orElseThrow(
                () -> new NotFoundException("No hall ticket for student " + studentId));
        return new HallTicketDto(row.id(), scheduleId, studentId, row.name(), row.admissionNo(),
            row.ticketNo(), row.seatNo(), row.issuedAt().toInstant(), sessionsForStudent(scheduleId, studentId));
    }
}
