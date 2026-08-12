package com.schoolsoft.attendance.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The school's marking window and its amendment approvers (ATT-06).
 *
 * A school with no row configured gets the defaults, which are the ones a
 * school would choose on day one: the register is editable for a day, and the
 * people who sign off a correction are the ones above the class teacher.
 */
@Repository
public class AttendancePolicyRepository {

    public record Policy(UUID schoolId, int editWindowHours, List<String> approverRoles) {}

    private static final int DEFAULT_WINDOW_HOURS = 24;
    private static final List<String> DEFAULT_APPROVERS =
        List.of("principal", "vice_principal", "academic_coordinator", "it_admin");

    private final JdbcTemplate jdbc;

    public AttendancePolicyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Policy of(UUID schoolId) {
        var rows = jdbc.query(
            "SELECT edit_window_hours, approver_roles FROM attendance_policy WHERE school_id = ?",
            (rs, i) -> new Policy(schoolId, rs.getInt("edit_window_hours"),
                List.of((String[]) rs.getArray("approver_roles").getArray())),
            schoolId);
        return rows.isEmpty() ? new Policy(schoolId, DEFAULT_WINDOW_HOURS, DEFAULT_APPROVERS) : rows.get(0);
    }

    public Policy upsert(UUID schoolId, Integer editWindowHours, List<String> approverRoles) {
        Policy current = of(schoolId);
        int window = editWindowHours == null ? current.editWindowHours() : editWindowHours;
        List<String> approvers = approverRoles == null || approverRoles.isEmpty()
            ? current.approverRoles() : approverRoles;
        for (String role : approvers) {
            if (!role.matches("^[a-z][a-z0-9_]*$")) {
                throw new IllegalArgumentException("Not a role code: " + role);
            }
        }
        jdbc.update(
            "INSERT INTO attendance_policy (school_id, edit_window_hours, approver_roles, updated_at) " +
            "VALUES (?, ?, ?::text[], now()) " +
            "ON CONFLICT (school_id) DO UPDATE SET edit_window_hours = EXCLUDED.edit_window_hours, " +
            "  approver_roles = EXCLUDED.approver_roles, updated_at = now()",
            schoolId, window, "{" + String.join(",", approvers) + "}");
        return of(schoolId);
    }

    /** Whether any of the caller's roles is allowed to decide amendments here. */
    public boolean mayApprove(UUID schoolId, List<String> roleCodes) {
        var allowed = of(schoolId).approverRoles();
        return roleCodes.stream().anyMatch(allowed::contains);
    }
}
