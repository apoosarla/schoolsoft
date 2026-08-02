package com.schoolsoft.attendance.internal;

import com.schoolsoft.attendance.api.AttendanceRecordDto;
import com.schoolsoft.attendance.api.LeaveApplicationDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AttendanceRepository {

    private final JdbcTemplate jdbc;
    public AttendanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
        UUID id = UUID.randomUUID();
        String conflictClause = periodNo == null
            ? "ON CONFLICT (student_id, on_date) WHERE period_no IS NULL DO UPDATE SET "
            : "ON CONFLICT (student_id, on_date, period_no) DO UPDATE SET ";
        jdbc.update(
            "INSERT INTO attendance_record (id, school_id, student_id, section_id, on_date, period_no, status, source, marked_by_staff_id, notes) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            conflictClause +
            "  status = EXCLUDED.status, source = EXCLUDED.source, marked_by_staff_id = EXCLUDED.marked_by_staff_id, " +
            "  marked_at = now(), notes = EXCLUDED.notes",
            id, schoolId, studentId, sectionId, Date.valueOf(onDate), periodNo, status, source, markedByStaffId, notes
        );
        return jdbc.queryForObject(
            "SELECT " + RECORD_COLS + " FROM attendance_record WHERE student_id = ? AND on_date = ? AND period_no IS NOT DISTINCT FROM ?",
            RECORD_MAPPER, studentId, Date.valueOf(onDate), periodNo
        );
    }

    public List<AttendanceRecordDto> forSectionOnDate(UUID sectionId, LocalDate onDate) {
        return jdbc.query(
            "SELECT " + RECORD_COLS + " FROM attendance_record WHERE section_id = ? AND on_date = ? ORDER BY student_id",
            RECORD_MAPPER, sectionId, Date.valueOf(onDate)
        );
    }

    public List<AttendanceRecordDto> forStudent(UUID studentId, LocalDate from, LocalDate to) {
        return jdbc.query(
            "SELECT " + RECORD_COLS + " FROM attendance_record WHERE student_id = ? AND on_date BETWEEN ? AND ? ORDER BY on_date, period_no",
            RECORD_MAPPER, studentId, Date.valueOf(from), Date.valueOf(to)
        );
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
