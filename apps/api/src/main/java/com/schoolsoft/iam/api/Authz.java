package com.schoolsoft.iam.api;

import com.schoolsoft.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Public authorization helper. Other modules call {@link #can(String, String, UUID)}
 * to assert that the current actor holds a role grant against a given scope.
 *
 * Role grants live in {@code staff_role} (per §5). For non-staff principals
 * (guardians, students), permissions are derived from the relationships in
 * {@code guardian_student} and {@code enrolment} — handled by callers, not here.
 */
@Service
public class Authz {

    private final DataSource dataSource;
    private final CampusScope campusScope;

    public Authz(DataSource dataSource, CampusScope campusScope) {
        this.dataSource = dataSource;
        this.campusScope = campusScope;
    }

    public boolean can(String roleCode, String scopeType, UUID scopeId) {
        var snap = TenantContext.get();
        if (snap == null) return false;
        if ("platform_admin".equals(snap.subjectType())) return true;
        if (!"staff".equals(snap.subjectType())) return false;

        var jdbc = new JdbcTemplate(dataSource);
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM staff_role sr " +
            "JOIN user_account ua ON ua.subject_id = sr.staff_id AND ua.subject_type = 'staff' " +
            "WHERE ua.id = ? AND sr.role_code = ? AND sr.scope_type = ? AND sr.scope_id = ? " +
            "AND sr.revoked_at IS NULL",
            Integer.class, snap.userAccountId(), roleCode, scopeType, scopeId
        );
        return n != null && n > 0;
    }

    /**
     * @deprecated moved to {@link CampusScope#ofCurrentUser()}. Campus scoping
     *     is a filter rather than a decision, and keeping it on {@code Authz}
     *     is what let it be reached from inside repositories alongside the
     *     role checks that do not belong there. Depend on {@code CampusScope}
     *     instead; this delegates.
     */
    @Deprecated
    public List<UUID> campusScopeOfCurrentUser() {
        return campusScope.ofCurrentUser();
    }

    /**
     * The staff row behind the current token, or null when the caller is not
     * staff. Modules that authorise against a teacher's duties (attendance,
     * cover) need the staff id, and the JWT carries only the user account.
     */
    public UUID currentStaffId() {
        var snap = TenantContext.get();
        if (snap == null || !"staff".equals(snap.subjectType()) || snap.userAccountId() == null) return null;
        var jdbc = new JdbcTemplate(dataSource);
        var rows = jdbc.query(
            "SELECT subject_id FROM user_account WHERE id = ? AND subject_type = 'staff'",
            (rs, i) -> rs.getString("subject_id") == null ? null : UUID.fromString(rs.getString("subject_id")),
            snap.userAccountId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<String> rolesOfCurrentUser() {
        var snap = TenantContext.get();
        if (snap == null || !"staff".equals(snap.subjectType())) return List.of();
        var jdbc = new JdbcTemplate(dataSource);
        return jdbc.queryForList(
            "SELECT DISTINCT sr.role_code FROM staff_role sr " +
            "JOIN user_account ua ON ua.subject_id = sr.staff_id AND ua.subject_type = 'staff' " +
            "WHERE ua.id = ? AND sr.revoked_at IS NULL",
            String.class, snap.userAccountId()
        );
    }
}
