package com.schoolsoft.attendance.internal;

import com.schoolsoft.attendance.api.AttendanceAmendmentDto;
import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.platform.web.NotFoundException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrections after the marking window (ATT-06, GAP-08).
 *
 * Inside the window a teacher fixes their own mistake and nobody needs to know.
 * Outside it the register has been read — by a parent, by a report, by the
 * attendance percentage a scholarship depends on — so a change becomes a
 * request that somebody senior signs, keeps the prior value, and lands in the
 * audit log with the reason attached.
 */
@Service
public class AttendanceAmendmentService {

    private final JdbcTemplate jdbc;
    private final AttendancePolicyRepository policies;
    private final AttendanceAuthorizer authorizer;
    private final Authz authz;

    public AttendanceAmendmentService(JdbcTemplate jdbc, AttendancePolicyRepository policies,
                                      AttendanceAuthorizer authorizer, Authz authz) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.authorizer = authorizer;
        this.authz = authz;
    }

    private static final String COLS =
        "id, school_id, attendance_record_id, student_id, section_id, on_date, period_no, old_status, " +
        "new_status, reason, requested_by_user_id, requested_at, status, decided_by_user_id, decided_at, " +
        "decision_note";

    private static final RowMapper<AttendanceAmendmentDto> MAPPER = (rs, i) -> new AttendanceAmendmentDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("attendance_record_id")),
        UUID.fromString(rs.getString("student_id")),
        UUID.fromString(rs.getString("section_id")),
        rs.getDate("on_date").toLocalDate(),
        (Integer) rs.getObject("period_no"),
        rs.getString("old_status"),
        rs.getString("new_status"),
        rs.getString("reason"),
        rs.getString("requested_by_user_id") == null ? null : UUID.fromString(rs.getString("requested_by_user_id")),
        rs.getTimestamp("requested_at").toInstant(),
        rs.getString("status"),
        rs.getString("decided_by_user_id") == null ? null : UUID.fromString(rs.getString("decided_by_user_id")),
        rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
        rs.getString("decision_note")
    );

    /** The record an amendment is about, with everything needed to validate one. */
    private record Record(UUID id, UUID schoolId, UUID studentId, UUID sectionId,
                          LocalDate onDate, Integer periodNo, String status) {}

    private Record record(UUID studentId, LocalDate onDate, Integer periodNo) {
        var rows = jdbc.query(
            "SELECT id, school_id, student_id, section_id, on_date, period_no, status " +
            "FROM attendance_record WHERE student_id = ? AND on_date = ? " +
            "  AND period_no IS NOT DISTINCT FROM ? AND voided_at IS NULL",
            (rs, i) -> new Record(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("school_id")),
                UUID.fromString(rs.getString("student_id")),
                UUID.fromString(rs.getString("section_id")),
                rs.getDate("on_date").toLocalDate(),
                (Integer) rs.getObject("period_no"),
                rs.getString("status")),
            studentId, Date.valueOf(onDate), periodNo);
        if (rows.isEmpty()) {
            throw new NotFoundException(
                "No attendance record for student " + studentId + " on " + onDate
                    + (periodNo == null ? "" : " period " + periodNo));
        }
        return rows.get(0);
    }

    public AttendanceAmendmentDto request(UUID studentId, LocalDate onDate, Integer periodNo,
                                          String newStatus, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An amendment needs a reason");
        }
        Record record = record(studentId, onDate, periodNo);
        authorizer.requireMayMark(record.sectionId(), onDate, periodNo);

        if (newStatus.equals(record.status())) {
            throw new IllegalArgumentException(
                "The register already says " + newStatus + " for that day");
        }

        Integer pending = jdbc.queryForObject(
            "SELECT count(*) FROM attendance_amendment WHERE attendance_record_id = ? AND status = 'pending'",
            Integer.class, record.id());
        if (pending != null && pending > 0) {
            throw new ConflictException("That register line already has an amendment awaiting a decision");
        }

        var snap = TenantContext.get();
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO attendance_amendment (id, school_id, attendance_record_id, student_id, section_id, " +
            "  on_date, period_no, old_status, new_status, reason, requested_by_user_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, record.schoolId(), record.id(), record.studentId(), record.sectionId(),
            Date.valueOf(record.onDate()), record.periodNo(), record.status(), newStatus, reason,
            snap == null ? null : snap.userAccountId());
        return find(id);
    }

    /**
     * Approval applies the new value; rejection leaves the register alone. Both
     * are decisions, so both are recorded — a rejected correction is evidence
     * that somebody looked.
     */
    @Transactional
    public AttendanceAmendmentDto decide(UUID id, String decision, String note) {
        AttendanceAmendmentDto amendment = find(id);
        if (!"pending".equals(amendment.status())) {
            throw new ConflictException("That amendment was already " + amendment.status());
        }
        if (!List.of("approved", "rejected").contains(decision)) {
            throw new IllegalArgumentException("A decision is 'approved' or 'rejected'");
        }
        requireApprover(amendment);

        var snap = TenantContext.get();
        jdbc.update(
            "UPDATE attendance_amendment SET status = ?, decided_by_user_id = ?, decided_at = now(), " +
            "  decision_note = ? WHERE id = ?",
            decision, snap == null ? null : snap.userAccountId(), note, id);

        if ("approved".equals(decision)) {
            jdbc.update(
                "UPDATE attendance_record SET status = ?, marked_at = now() WHERE id = ?",
                amendment.newStatus(), amendment.attendanceRecordId());
        }
        return find(id);
    }

    /**
     * The approver holds one of the school's approving roles and is not the
     * person who asked. A correction one person can both request and grant is
     * not a workflow, it is a slower edit.
     */
    private void requireApprover(AttendanceAmendmentDto amendment) {
        var snap = TenantContext.get();
        if (snap == null || !"staff".equals(snap.subjectType())) {
            throw new ForbiddenException("Only staff may decide an attendance amendment");
        }
        if (snap.userAccountId() != null && snap.userAccountId().equals(amendment.requestedByUserId())) {
            throw new ForbiddenException("An amendment is decided by somebody other than the person who raised it");
        }
        if (!policies.mayApprove(amendment.schoolId(), authz.rolesOfCurrentUser())) {
            throw new ForbiddenException(
                "Your role cannot decide attendance amendments in this school");
        }
    }

    public AttendanceAmendmentDto find(UUID id) {
        var rows = jdbc.query("SELECT " + COLS + " FROM attendance_amendment WHERE id = ?", MAPPER, id);
        if (rows.isEmpty()) throw new NotFoundException("Amendment not found: " + id);
        return rows.get(0);
    }

    public List<AttendanceAmendmentDto> list(UUID schoolId, String status, UUID studentId) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM attendance_amendment WHERE school_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(schoolId);
        if (status != null) { sql.append(" AND status = ?"); args.add(status); }
        if (studentId != null) { sql.append(" AND student_id = ?"); args.add(studentId); }
        sql.append(" ORDER BY requested_at DESC");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * Whether an existing record is still inside its school's marking window.
     * Used by the marking path to decide between a straight correction and an
     * amendment.
     */
    public boolean withinEditWindow(UUID schoolId, java.time.Instant markedAt) {
        int hours = policies.of(schoolId).editWindowHours();
        return markedAt.plusSeconds(hours * 3600L).isAfter(java.time.Instant.now());
    }
}
