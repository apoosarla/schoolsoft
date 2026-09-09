package com.schoolsoft.enrolment.internal;

import com.schoolsoft.enrolment.api.ClearanceItemDto;
import com.schoolsoft.enrolment.api.WithdrawalDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** SQL for {@code withdrawal} and its checklist. Decisions live in the service. */
@Repository
public class WithdrawalRepository {

    private final JdbcTemplate jdbc;

    public WithdrawalRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String COLS =
        "id, school_id, student_id, enrolment_id, reason_code, reason, requested_on, last_working_date, " +
        "state, dues_override_reason, dues_override_by_staff_id, completed_at, cancelled_reason";

    private static final RowMapper<WithdrawalDto> MAPPER = (rs, i) -> new WithdrawalDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("student_id")),
        UUID.fromString(rs.getString("enrolment_id")),
        rs.getString("reason_code"),
        rs.getString("reason"),
        rs.getDate("requested_on").toLocalDate(),
        rs.getDate("last_working_date").toLocalDate(),
        rs.getString("state"),
        rs.getString("dues_override_reason"),
        rs.getString("dues_override_by_staff_id") == null
            ? null : UUID.fromString(rs.getString("dues_override_by_staff_id")),
        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
        rs.getString("cancelled_reason"),
        List.of()
    );

    private static final RowMapper<ClearanceItemDto> ITEM_MAPPER = (rs, i) -> new ClearanceItemDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("withdrawal_id")),
        rs.getString("area"),
        rs.getString("state"),
        rs.getString("detail"),
        rs.getObject("amount") == null ? null : rs.getDouble("amount"),
        rs.getTimestamp("checked_at") == null ? null : rs.getTimestamp("checked_at").toInstant(),
        rs.getString("resolved_by_staff_id") == null
            ? null : UUID.fromString(rs.getString("resolved_by_staff_id")),
        rs.getString("resolved_reason"),
        rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant()
    );

    // ------------------------------------------------------------------ reads

    /** The withdrawal with its checklist attached, or 404. */
    public WithdrawalDto require(UUID id) {
        var rows = jdbc.query("SELECT " + COLS + " FROM withdrawal WHERE id = ?", MAPPER, id);
        if (rows.isEmpty()) throw new NotFoundException("Withdrawal not found: " + id);
        return withItems(rows.get(0));
    }

    public WithdrawalDto withItems(WithdrawalDto withdrawal) {
        return new WithdrawalDto(
            withdrawal.id(), withdrawal.schoolId(), withdrawal.studentId(), withdrawal.enrolmentId(),
            withdrawal.reasonCode(), withdrawal.reason(), withdrawal.requestedOn(),
            withdrawal.lastWorkingDate(), withdrawal.state(), withdrawal.duesOverrideReason(),
            withdrawal.duesOverrideByStaffId(), withdrawal.completedAt(), withdrawal.cancelledReason(),
            items(withdrawal.id()));
    }

    public List<ClearanceItemDto> items(UUID withdrawalId) {
        return jdbc.query(
            "SELECT id, withdrawal_id, area, state, detail, amount, checked_at, resolved_by_staff_id, " +
            "       resolved_reason, resolved_at " +
            "FROM clearance_item WHERE withdrawal_id = ? ORDER BY area",
            ITEM_MAPPER, withdrawalId);
    }

    public List<WithdrawalDto> list(UUID schoolId, String state) {
        String sql = "SELECT " + COLS + " FROM withdrawal WHERE school_id = ?"
            + (state == null ? "" : " AND state = ?") + " ORDER BY requested_on DESC, created_at DESC";
        List<WithdrawalDto> rows = state == null
            ? jdbc.query(sql, MAPPER, schoolId)
            : jdbc.query(sql, MAPPER, schoolId, state);
        return rows.stream().map(this::withItems).toList();
    }

    public List<WithdrawalDto> listForStudent(UUID studentId) {
        return jdbc.query("SELECT " + COLS + " FROM withdrawal WHERE student_id = ? ORDER BY requested_on DESC",
                MAPPER, studentId)
            .stream().map(this::withItems).toList();
    }

    /** The one withdrawal still in flight for a student, if any. */
    public List<WithdrawalDto> openForStudent(UUID studentId) {
        return jdbc.query(
            "SELECT " + COLS + " FROM withdrawal WHERE student_id = ? " +
            "  AND state IN ('draft','clearance_pending','cleared')", MAPPER, studentId);
    }

    // ----------------------------------------------------------------- writes

    public UUID insert(UUID schoolId, UUID studentId, UUID enrolmentId, String reasonCode, String reason,
                       LocalDate lastWorkingDate, UUID initiatedByStaffId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO withdrawal (id, school_id, student_id, enrolment_id, reason_code, reason, " +
            "  last_working_date, state, initiated_by_staff_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'clearance_pending', ?)",
            id, schoolId, studentId, enrolmentId, reasonCode, reason,
            Date.valueOf(lastWorkingDate), initiatedByStaffId);
        return id;
    }

    /**
     * Writes what a probe found. The probe is the authority on its own area, so
     * this overwrites what is there — including a line somebody ticked by hand.
     * Anything else lets a stale tick outlive the fact it recorded: clear the
     * fees line, return a book that posts an overdue fine, and the checklist
     * would go on saying the family owes nothing.
     *
     * <p>A {@code waived} line is the exception, because it is not a claim about
     * the world at all. It is a named person deciding to let the debt go, and no
     * amount of re-probing overturns that.</p>
     */
    public void upsertProbedItem(UUID schoolId, UUID withdrawalId, String area, String state,
                                 String detail, Double amount) {
        int updated = jdbc.update(
            "UPDATE clearance_item SET state = ?, detail = ?, amount = ?, checked_at = now() " +
            "WHERE withdrawal_id = ? AND area = ? AND state <> 'waived'",
            state, detail, amount, withdrawalId, area);
        if (updated == 0) {
            jdbc.update(
                "INSERT INTO clearance_item (school_id, withdrawal_id, area, state, detail, amount, checked_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, now()) ON CONFLICT (withdrawal_id, area) DO NOTHING",
                schoolId, withdrawalId, area, state, detail, amount);
        }
    }

    /**
     * Opens a line for an area nothing probes, and never touches it again — the
     * sign-off is a person's, and a refresh must not reset it to pending.
     */
    public void ensureManualItem(UUID schoolId, UUID withdrawalId, String area, String detail) {
        jdbc.update(
            "INSERT INTO clearance_item (school_id, withdrawal_id, area, state, detail) " +
            "VALUES (?, ?, ?, 'pending', ?) ON CONFLICT (withdrawal_id, area) DO NOTHING",
            schoolId, withdrawalId, area, detail);
    }

    /** Marks one line cleared or waived. Returns false when there is no such line. */
    public boolean resolveItem(UUID withdrawalId, String area, String state, String reason, UUID staffId) {
        return jdbc.update(
            "UPDATE clearance_item SET state = ?, resolved_reason = ?, resolved_by_staff_id = ?, " +
            "  resolved_at = now() WHERE withdrawal_id = ? AND area = ?",
            state, reason, staffId, withdrawalId, area) == 1;
    }

    /**
     * Moves the withdrawal out of {@code from} into {@code to}. Zero rows means
     * it was not in {@code from} — somebody else moved it, or it never was.
     */
    public int transition(UUID id, List<String> from, String to) {
        String placeholders = String.join(",", java.util.Collections.nCopies(from.size(), "?"));
        Object[] args = new Object[from.size() + 2];
        args[0] = to;
        args[1] = id;
        for (int i = 0; i < from.size(); i++) args[i + 2] = from.get(i);
        return jdbc.update("UPDATE withdrawal SET state = ? WHERE id = ? AND state IN (" + placeholders + ")", args);
    }

    public int complete(UUID id, UUID staffId) {
        return jdbc.update(
            "UPDATE withdrawal SET state = 'completed', completed_at = now(), completed_by_staff_id = ? " +
            "WHERE id = ? AND state IN ('clearance_pending','cleared')", staffId, id);
    }

    public int cancel(UUID id, String reason) {
        return jdbc.update(
            "UPDATE withdrawal SET state = 'cancelled', cancelled_reason = ?, cancelled_at = now() " +
            "WHERE id = ? AND state IN ('draft','clearance_pending','cleared')", reason, id);
    }

    public void recordDuesOverride(UUID id, String reason, UUID staffId) {
        jdbc.update(
            "UPDATE withdrawal SET dues_override_reason = ?, dues_override_by_staff_id = ?, " +
            "  dues_override_at = now() WHERE id = ?", reason, staffId, id);
    }

    /**
     * Closes the enrolment on the last working day, naming the state it moves
     * out of. Zero rows means it was already closed, which a retry must treat as
     * success rather than as a failure.
     */
    public int closeEnrolment(UUID enrolmentId, String status, LocalDate lastWorkingDate) {
        return jdbc.update(
            "UPDATE enrolment SET status = ?, ends_on = ? WHERE id = ? AND status = 'active'",
            status, Date.valueOf(lastWorkingDate), enrolmentId);
    }

    /**
     * The student row follows the enrolment. It carries its own status because
     * the admissions and directory screens key off the person rather than off
     * whichever enrolment happens to be newest.
     */
    public void setStudentStatus(UUID studentId, String status) {
        jdbc.update("UPDATE student SET status = ?, updated_at = now() WHERE id = ?", status, studentId);
    }
}
