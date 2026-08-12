package com.schoolsoft.attendance.internal;

import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.schoolcalendar.api.WorkingDayService;
import com.schoolsoft.tenancy.api.AcademicYearGuard;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an approved leave into the attendance it stands for (GAP-08, ATT-05,
 * ATT-13).
 *
 * Before this, a school that approved a week's leave still had a teacher
 * marking that child absent every morning — and every attendance percentage in
 * the school was wrong by however often somebody forgot. Approval now writes
 * the days, and revoking the approval takes them back.
 *
 * Two rules make the unwind honest. Days this service created are deleted on
 * revoke; days it *changed* are restored to what they were, because the prior
 * value is kept in {@code attendance_amendment} rather than overwritten. A
 * leave approval is, after all, exactly an amendment that the school has
 * already agreed to.
 */
@Service
public class LeaveMaterialisationService {

    private final JdbcTemplate jdbc;
    private final WorkingDayService workingDays;
    private final AcademicYearGuard academicYears;

    public LeaveMaterialisationService(JdbcTemplate jdbc, WorkingDayService workingDays,
                                       AcademicYearGuard academicYears) {
        this.jdbc = jdbc;
        this.workingDays = workingDays;
        this.academicYears = academicYears;
    }

    private record Leave(UUID id, UUID schoolId, String subjectType, UUID subjectId,
                         LocalDate fromDate, LocalDate toDate, String reason) {}

    private Leave leave(UUID leaveId) {
        var rows = jdbc.query(
            "SELECT id, school_id, subject_type, subject_id, from_date, to_date, reason " +
            "FROM leave_application WHERE id = ?",
            (rs, i) -> new Leave(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("school_id")),
                rs.getString("subject_type"),
                UUID.fromString(rs.getString("subject_id")),
                rs.getDate("from_date").toLocalDate(),
                rs.getDate("to_date").toLocalDate(),
                rs.getString("reason")),
            leaveId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Leave application not found: " + leaveId);
        return rows.get(0);
    }

    /** Writes the leave days. Returns how many days were written. */
    @Transactional
    public int materialise(UUID leaveId) {
        Leave leave = leave(leaveId);
        // A leave that lands in a closed year would rewrite closed history.
        academicYears.requireOpenOn(leave.schoolId(), leave.fromDate());
        academicYears.requireOpenOn(leave.schoolId(), leave.toDate());

        int days = "staff".equals(leave.subjectType())
            ? materialiseStaff(leave)
            : materialiseStudent(leave);

        jdbc.update("UPDATE leave_application SET materialised_at = now(), materialised_days = ? WHERE id = ?",
            days, leaveId);
        return days;
    }

    /**
     * Student leave follows the enrolment, not the school: a child who changes
     * section mid-leave has each day written against the section they were in.
     */
    private int materialiseStudent(Leave leave) {
        record Segment(UUID schoolId, UUID sectionId, UUID gradeId, UUID campusId,
                       LocalDate startsOn, LocalDate endsOn) {}

        List<Segment> segments = jdbc.query(
            "SELECT e.school_id, e.section_id, s.grade_id, s.campus_id, e.starts_on, e.ends_on " +
            "FROM enrolment e JOIN section s ON s.id = e.section_id " +
            "WHERE e.student_id = ? AND e.starts_on <= ? AND COALESCE(e.ends_on, 'infinity'::date) >= ? " +
            "ORDER BY e.starts_on",
            (rs, i) -> new Segment(
                UUID.fromString(rs.getString("school_id")),
                UUID.fromString(rs.getString("section_id")),
                UUID.fromString(rs.getString("grade_id")),
                rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id")),
                rs.getDate("starts_on").toLocalDate(),
                rs.getDate("ends_on") == null ? null : rs.getDate("ends_on").toLocalDate()),
            leave.subjectId(), Date.valueOf(leave.toDate()), Date.valueOf(leave.fromDate()));

        int written = 0;
        for (Segment segment : segments) {
            LocalDate from = segment.startsOn().isAfter(leave.fromDate()) ? segment.startsOn() : leave.fromDate();
            LocalDate to = segment.endsOn() == null || segment.endsOn().isAfter(leave.toDate())
                ? leave.toDate() : segment.endsOn();
            if (to.isBefore(from)) continue;

            for (LocalDate date : workingDays.workingDays(
                    segment.schoolId(), from, to, segment.gradeId(), segment.campusId())) {
                written += writeStudentDay(leave, segment.schoolId(), segment.sectionId(), date);
            }
        }
        return written;
    }

    /** One day: a fresh row, or an amendment over what the teacher had marked. */
    private int writeStudentDay(Leave leave, UUID schoolId, UUID sectionId, LocalDate date) {
        record Existing(UUID id, String status) {}
        var existing = jdbc.query(
            "SELECT id, status FROM attendance_record WHERE student_id = ? AND on_date = ? " +
            "  AND period_no IS NULL AND voided_at IS NULL",
            (rs, i) -> new Existing(UUID.fromString(rs.getString("id")), rs.getString("status")),
            leave.subjectId(), Date.valueOf(date));

        if (existing.isEmpty()) {
            jdbc.update(
                "INSERT INTO attendance_record (id, school_id, student_id, section_id, on_date, period_no, " +
                "  status, source, notes, leave_application_id) " +
                "VALUES (?, ?, ?, ?, ?, NULL, 'leave', 'auto', ?, ?)",
                UUID.randomUUID(), schoolId, leave.subjectId(), sectionId, Date.valueOf(date),
                "Approved leave" + (leave.reason() == null ? "" : ": " + leave.reason()), leave.id());
            return 1;
        }

        Existing record = existing.get(0);
        if ("leave".equals(record.status())) {
            jdbc.update("UPDATE attendance_record SET leave_application_id = ? WHERE id = ?",
                leave.id(), record.id());
            return 1;
        }

        var snap = TenantContext.get();
        jdbc.update(
            "INSERT INTO attendance_amendment (id, school_id, attendance_record_id, student_id, section_id, " +
            "  on_date, period_no, old_status, new_status, reason, requested_by_user_id, status, " +
            "  decided_by_user_id, decided_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, NULL, ?, 'leave', ?, ?, 'approved', ?, now())",
            UUID.randomUUID(), schoolId, record.id(), leave.subjectId(), sectionId, Date.valueOf(date),
            record.status(), "Leave approved" + (leave.reason() == null ? "" : ": " + leave.reason()),
            snap == null ? null : snap.userAccountId(), snap == null ? null : snap.userAccountId());
        jdbc.update(
            "UPDATE attendance_record SET status = 'leave', leave_application_id = ?, marked_at = now() " +
            "WHERE id = ?", leave.id(), record.id());
        return 1;
    }

    /** Staff leave lands in staff_attendance, which is the register a payroll run reads. */
    private int materialiseStaff(Leave leave) {
        var campuses = jdbc.query("SELECT campus_id FROM staff WHERE id = ?",
            (rs, i) -> rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id")),
            leave.subjectId());
        UUID campusId = campuses.isEmpty() ? null : campuses.get(0);

        int written = 0;
        for (LocalDate date : workingDays.workingDays(
                leave.schoolId(), leave.fromDate(), leave.toDate(), null, campusId)) {
            jdbc.update(
                "INSERT INTO staff_attendance (id, school_id, staff_id, on_date, status, source, " +
                "  leave_application_id, notes) " +
                "VALUES (?, ?, ?, ?, 'leave', 'auto', ?, ?) " +
                "ON CONFLICT (staff_id, on_date) DO UPDATE SET status = 'leave', source = 'auto', " +
                "  leave_application_id = EXCLUDED.leave_application_id, marked_at = now(), " +
                "  notes = EXCLUDED.notes",
                UUID.randomUUID(), leave.schoolId(), leave.subjectId(), Date.valueOf(date), leave.id(),
                "Approved leave" + (leave.reason() == null ? "" : ": " + leave.reason()));
            written++;
        }
        return written;
    }

    /**
     * Takes the days back when an approval is withdrawn. What this service
     * created is deleted; what it changed is restored to the value the
     * amendment kept.
     */
    @Transactional
    public int unwind(UUID leaveId) {
        Leave leave = leave(leaveId);
        int removed;

        if ("staff".equals(leave.subjectType())) {
            removed = jdbc.update(
                "DELETE FROM staff_attendance WHERE leave_application_id = ? AND source = 'auto'", leaveId);
        } else {
            record Amended(UUID recordId, UUID amendmentId, String oldStatus) {}
            List<Amended> amended = jdbc.query(
                "SELECT a.attendance_record_id, a.id AS amendment_id, a.old_status " +
                "FROM attendance_amendment a JOIN attendance_record r ON r.id = a.attendance_record_id " +
                "WHERE r.leave_application_id = ? AND a.status = 'approved' AND a.new_status = 'leave'",
                (rs, i) -> new Amended(
                    UUID.fromString(rs.getString("attendance_record_id")),
                    UUID.fromString(rs.getString("amendment_id")),
                    rs.getString("old_status")),
                leaveId);
            for (Amended row : amended) {
                jdbc.update(
                    "UPDATE attendance_record SET status = ?, leave_application_id = NULL, marked_at = now() " +
                    "WHERE id = ?", row.oldStatus(), row.recordId());
                jdbc.update(
                    "UPDATE attendance_amendment SET status = 'withdrawn', decided_at = now(), " +
                    "  decision_note = 'Leave approval revoked' WHERE id = ?", row.amendmentId());
            }
            removed = amended.size() + jdbc.update(
                "DELETE FROM attendance_record WHERE leave_application_id = ? AND source = 'auto'", leaveId);
        }

        jdbc.update("UPDATE leave_application SET materialised_at = NULL, materialised_days = 0 WHERE id = ?",
            leaveId);
        return removed;
    }
}
