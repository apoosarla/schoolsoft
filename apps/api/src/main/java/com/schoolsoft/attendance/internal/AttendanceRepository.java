package com.schoolsoft.attendance.internal;

import com.schoolsoft.attendance.api.AttendanceRecordDto;
import com.schoolsoft.attendance.api.AttendanceSummaryDto;
import com.schoolsoft.attendance.api.LeaveApplicationDto;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.schoolcalendar.api.WorkingDayService;
import com.schoolsoft.tenancy.api.AcademicYearGuard;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AttendanceRepository {

    private final JdbcTemplate jdbc;
    private final WorkingDayService workingDays;
    private final AcademicYearGuard academicYears;

    public AttendanceRepository(JdbcTemplate jdbc, WorkingDayService workingDays,
                                AcademicYearGuard academicYears) {
        this.jdbc = jdbc;
        this.workingDays = workingDays;
        this.academicYears = academicYears;
    }

    /** A section's cohort scope, which is what a calendar entry can be narrowed to. */
    private record SectionScope(UUID gradeId, UUID campusId) {}

    /**
     * "Today" as the school experiences it. A server in UTC and a school in
     * IST disagree for five and a half hours every evening, which is long
     * enough to make a legitimate late-evening mark look like a future date.
     */
    private LocalDate todayAt(UUID schoolId) {
        var zones = jdbc.queryForList("SELECT timezone FROM school WHERE id = ?", String.class, schoolId);
        String zone = zones.isEmpty() || zones.get(0) == null ? null : zones.get(0);
        try {
            return LocalDate.now(zone == null ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(zone));
        } catch (java.time.DateTimeException e) {
            return LocalDate.now();
        }
    }

    /** Refuses a mark for a date the student was not enrolled in that section on (ATT-12). */
    private void requireEnrolledOn(UUID studentId, UUID sectionId, LocalDate onDate) {
        Integer enrolled = jdbc.queryForObject(
            "SELECT count(*) FROM enrolment WHERE student_id = ? AND section_id = ? " +
            "  AND starts_on <= ? AND COALESCE(ends_on, 'infinity'::date) >= ?",
            Integer.class, studentId, sectionId, Date.valueOf(onDate), Date.valueOf(onDate));
        if (enrolled == null || enrolled == 0) {
            throw new IllegalArgumentException(
                "Student " + studentId + " was not enrolled in section " + sectionId + " on " + onDate);
        }
    }

    private SectionScope scopeOf(UUID sectionId) {
        var rows = jdbc.query(
            "SELECT grade_id, campus_id FROM section WHERE id = ?",
            (rs, i) -> new SectionScope(
                UUID.fromString(rs.getString("grade_id")),
                rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id"))),
            sectionId);
        if (rows.isEmpty()) throw new NotFoundException("Section not found: " + sectionId);
        return rows.get(0);
    }

    private static final RowMapper<AttendanceRecordDto> RECORD_MAPPER = (rs, i) -> new AttendanceRecordDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("student_id")),
        UUID.fromString(rs.getString("section_id")),
        rs.getDate("on_date").toLocalDate(),
        (Integer) rs.getObject("period_no"),
        rs.getString("status"),
        rs.getString("source"),
        rs.getString("notes")
    );

    private static final String RECORD_COLS =
        "id, school_id, student_id, section_id, on_date, period_no, status, source, notes";

    /**
     * Upserts on {@code (student_id, on_date, period_no)}. Period-level marks
     * use the table's plain unique constraint; day-level marks
     * ({@code periodNo == null}) use the partial unique index from
     * V010, since Postgres does not treat two NULLs as conflicting under a
     * plain unique constraint.
     */
    public AttendanceRecordDto mark(
        UUID schoolId, UUID studentId, UUID sectionId, LocalDate onDate, Integer periodNo,
        String status, String source, UUID markedByStaffId, String notes
    ) {
        academicYears.requireOpenOn(schoolId, onDate);

        // Attendance is a record of something that happened. A date the school
        // has not reached yet, or one outside the student's own enrolment
        // window, is a mis-keyed form rather than a fact (ATT-12).
        LocalDate today = todayAt(schoolId);
        if (onDate.isAfter(today)) {
            throw new IllegalArgumentException(
                "Attendance cannot be marked for a future date: " + onDate + " (today is " + today + ")");
        }
        requireEnrolledOn(studentId, sectionId, onDate);

        // A day the school is not open on has no attendance to take, and letting
        // one through would corrupt every percentage computed off the same
        // calendar (GAP-01).
        SectionScope scope = scopeOf(sectionId);
        var day = workingDays.statusOf(schoolId, onDate, scope.gradeId(), scope.campusId());
        if (!day.working()) {
            throw new IllegalArgumentException(
                "Attendance cannot be marked on " + onDate + ": " + day.reason());
        }

        UUID id = UUID.randomUUID();
        String conflictClause = periodNo == null
            ? "ON CONFLICT (student_id, on_date) WHERE period_no IS NULL AND voided_at IS NULL DO UPDATE SET "
            : "ON CONFLICT (student_id, on_date, period_no) WHERE period_no IS NOT NULL AND voided_at IS NULL DO UPDATE SET ";
        jdbc.update(
            "INSERT INTO attendance_record (id, school_id, student_id, section_id, on_date, period_no, status, source, marked_by_staff_id, notes) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            conflictClause +
            "  status = EXCLUDED.status, source = EXCLUDED.source, marked_by_staff_id = EXCLUDED.marked_by_staff_id, " +
            "  marked_at = now(), notes = EXCLUDED.notes",
            id, schoolId, studentId, sectionId, Date.valueOf(onDate), periodNo, status, source, markedByStaffId, notes
        );
        return jdbc.queryForObject(
            "SELECT " + RECORD_COLS + " FROM attendance_record WHERE student_id = ? AND on_date = ? " +
            "  AND period_no IS NOT DISTINCT FROM ? AND voided_at IS NULL",
            RECORD_MAPPER, studentId, Date.valueOf(onDate), periodNo
        );
    }

    public List<AttendanceRecordDto> forSectionOnDate(UUID sectionId, LocalDate onDate) {
        return jdbc.query(
            "SELECT " + RECORD_COLS + " FROM attendance_record WHERE section_id = ? AND on_date = ? " +
            "  AND voided_at IS NULL ORDER BY student_id",
            RECORD_MAPPER, sectionId, Date.valueOf(onDate)
        );
    }

    public List<AttendanceRecordDto> forStudent(UUID studentId, LocalDate from, LocalDate to) {
        return jdbc.query(
            "SELECT " + RECORD_COLS + " FROM attendance_record WHERE student_id = ? AND on_date BETWEEN ? AND ? " +
            "  AND voided_at IS NULL ORDER BY on_date, period_no",
            RECORD_MAPPER, studentId, Date.valueOf(from), Date.valueOf(to)
        );
    }

    /**
     * Attendance percentage over a range (ATT-04, ATT-10).
     *
     * Two things make this different from counting rows. The denominator is
     * working days from {@link WorkingDayService}, not calendar days, so
     * holidays and weekends never count against a child. And it is computed per
     * enrolment segment, so a mid-year joiner is measured from the day they
     * joined and a leaver up to the day they left, each against their own
     * section's calendar scope.
     *
     * Half-days count as half a day present; late counts as present (the
     * student was there); leave and excused are removed from the denominator
     * rather than counted as absence.
     */
    public AttendanceSummaryDto summaryForStudent(UUID studentId, LocalDate from, LocalDate to) {
        record Segment(UUID schoolId, UUID sectionId, UUID gradeId, UUID campusId,
                       LocalDate startsOn, LocalDate endsOn) {}

        List<Segment> segments = jdbc.query(
            "SELECT e.school_id, e.section_id, s.grade_id, s.campus_id, e.starts_on, e.ends_on " +
            "FROM enrolment e JOIN section s ON s.id = e.section_id " +
            "WHERE e.student_id = ? ORDER BY e.starts_on",
            (rs, i) -> new Segment(
                UUID.fromString(rs.getString("school_id")),
                UUID.fromString(rs.getString("section_id")),
                UUID.fromString(rs.getString("grade_id")),
                rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id")),
                rs.getDate("starts_on").toLocalDate(),
                rs.getDate("ends_on") == null ? null : rs.getDate("ends_on").toLocalDate()),
            studentId);

        int workingDayCount = 0;
        LocalDate enrolledFrom = null;
        LocalDate enrolledTo = null;
        for (Segment segment : segments) {
            LocalDate windowStart = segment.startsOn().isAfter(from) ? segment.startsOn() : from;
            LocalDate windowEnd = segment.endsOn() == null || segment.endsOn().isAfter(to) ? to : segment.endsOn();
            if (windowEnd.isBefore(windowStart)) continue;      // segment outside the asked range
            workingDayCount += workingDays.countWorkingDays(
                segment.schoolId(), windowStart, windowEnd, segment.gradeId(), segment.campusId());
            if (enrolledFrom == null || windowStart.isBefore(enrolledFrom)) enrolledFrom = windowStart;
            if (enrolledTo == null || windowEnd.isAfter(enrolledTo)) enrolledTo = windowEnd;
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query(
            "SELECT status, count(*) AS n FROM attendance_record " +
            "WHERE student_id = ? AND on_date BETWEEN ? AND ? AND period_no IS NULL AND voided_at IS NULL " +
            "GROUP BY status",
            rs -> { counts.put(rs.getString("status"), rs.getInt("n")); },
            studentId, Date.valueOf(from), Date.valueOf(to));

        int present = counts.getOrDefault("present", 0);
        int late = counts.getOrDefault("late", 0);
        int halfDay = counts.getOrDefault("half_day", 0);
        int absent = counts.getOrDefault("absent", 0);
        int leave = counts.getOrDefault("leave", 0);
        int excused = counts.getOrDefault("excused", 0);

        // Leave and excused days are neither present nor held against the student.
        int consideredDays = Math.max(0, workingDayCount - leave - excused);
        double attended = present + late + (halfDay * 0.5);
        Double percentage = consideredDays == 0 ? null
            : Math.round(attended * 10000.0 / consideredDays) / 100.0;

        return new AttendanceSummaryDto(
            studentId, from, to, enrolledFrom, enrolledTo,
            workingDayCount, consideredDays,
            present, absent, late, halfDay, leave, excused, percentage);
    }

    // -------------------------- Leave --------------------------

    private static final RowMapper<LeaveApplicationDto> LEAVE_MAPPER = (rs, i) -> new LeaveApplicationDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("subject_type"),
        UUID.fromString(rs.getString("subject_id")),
        rs.getDate("from_date").toLocalDate(),
        rs.getDate("to_date").toLocalDate(),
        rs.getString("reason"),
        rs.getString("status"),
        rs.getString("approver_staff_id") == null ? null : UUID.fromString(rs.getString("approver_staff_id"))
    );

    private static final String LEAVE_COLS =
        "id, school_id, subject_type, subject_id, from_date, to_date, reason, status, approver_staff_id";

    public LeaveApplicationDto applyLeave(
        UUID schoolId, String subjectType, UUID subjectId, LocalDate fromDate, LocalDate toDate, String reason
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO leave_application (id, school_id, subject_type, subject_id, from_date, to_date, reason) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, subjectType, subjectId, Date.valueOf(fromDate), Date.valueOf(toDate), reason
        );
        return jdbc.queryForObject("SELECT " + LEAVE_COLS + " FROM leave_application WHERE id = ?", LEAVE_MAPPER, id);
    }

    public List<LeaveApplicationDto> listLeave(UUID schoolId, String status) {
        String sql = "SELECT " + LEAVE_COLS + " FROM leave_application WHERE school_id = ?" +
            (status == null ? "" : " AND status = ?") + " ORDER BY from_date DESC";
        return status == null ? jdbc.query(sql, LEAVE_MAPPER, schoolId) : jdbc.query(sql, LEAVE_MAPPER, schoolId, status);
    }

    public LeaveApplicationDto decideLeave(UUID id, String status, UUID approverStaffId) {
        int updated = jdbc.update(
            "UPDATE leave_application SET status = ?, approver_staff_id = ?, decided_at = now() WHERE id = ?",
            status, approverStaffId, id
        );
        if (updated == 0) throw new NotFoundException("Leave application not found: " + id);
        return jdbc.queryForObject("SELECT " + LEAVE_COLS + " FROM leave_application WHERE id = ?", LEAVE_MAPPER, id);
    }
}
