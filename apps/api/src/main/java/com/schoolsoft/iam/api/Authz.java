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

    public Authz(DataSource dataSource) { this.dataSource = dataSource; }

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
