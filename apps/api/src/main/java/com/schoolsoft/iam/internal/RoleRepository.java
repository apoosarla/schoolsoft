package com.schoolsoft.iam.internal;

import com.schoolsoft.iam.api.RoleDto;
import com.schoolsoft.iam.api.StaffWithRolesDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RoleRepository {

    private final JdbcTemplate jdbc;
    public RoleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private Array textArray(List<String> values) {
        return jdbc.execute((ConnectionCallback<Array>) con ->
            con.createArrayOf("text", values == null ? new String[0] : values.toArray())
        );
    }

    private static List<String> readTextArray(ResultSet rs, String col) throws SQLException {
        Array arr = rs.getArray(col);
        if (arr == null) return List.of();
        Object[] raw = (Object[]) arr.getArray();
        return Arrays.stream(raw).map(Object::toString).toList();
    }

    private static final RowMapper<RoleDto> ROLE_MAPPER = (rs, i) -> new RoleDto(
        UUID.fromString(rs.getString("id")),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("description"),
        readTextArray(rs, "screen_keys"),
        rs.getBoolean("is_system"),
        rs.getTimestamp("created_at").toInstant()
    );

    private static final String ROLE_COLS = "id, code, name, description, screen_keys, is_system, created_at";

    public List<RoleDto> listRoles() {
        return jdbc.query("SELECT " + ROLE_COLS + " FROM role ORDER BY is_system DESC, name", ROLE_MAPPER);
    }

    public Optional<RoleDto> findRole(UUID id) {
        var rows = jdbc.query("SELECT " + ROLE_COLS + " FROM role WHERE id = ?", ROLE_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public RoleDto createRole(String code, String name, String description, List<String> screenKeys) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO role (id, code, name, description, screen_keys, is_system) VALUES (?, ?, ?, ?, ?, FALSE)",
            id, code, name, description, textArray(screenKeys)
        );
        return findRole(id).orElseThrow();
    }

    public RoleDto updateRole(UUID id, String name, String description, List<String> screenKeys) {
        int updated = jdbc.update(
            "UPDATE role SET name = ?, description = ?, screen_keys = ? WHERE id = ?",
            name, description, textArray(screenKeys), id
        );
        if (updated == 0) throw new NotFoundException("Role not found: " + id);
        return findRole(id).orElseThrow();
    }

    public void deleteRole(UUID id) {
        var role = findRole(id).orElseThrow(() -> new NotFoundException("Role not found: " + id));
        if (role.isSystem()) {
            throw new IllegalArgumentException("System role '" + role.code() + "' cannot be deleted.");
        }
        jdbc.update("DELETE FROM role WHERE id = ?", id);
    }

    /** Union of screen_keys across every role code the caller holds, unnested and de-duplicated in SQL. */
    public List<String> screensForRoleCodes(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) return List.of();
        return jdbc.queryForList(
            "SELECT DISTINCT unnest(screen_keys) FROM role WHERE code = ANY(?)",
            String.class, textArray(roleCodes)
        );
    }

    public List<StaffWithRolesDto> listStaffWithRoles(UUID schoolId) {
        return jdbc.query(
            "SELECT s.id AS staff_id, ua.id AS user_account_id, s.first_name, s.last_name, s.email, " +
            "       COALESCE(array_agg(sr.role_code) FILTER (WHERE sr.role_code IS NOT NULL), '{}') AS role_codes " +
            "FROM staff s " +
            "LEFT JOIN user_account ua ON ua.subject_type = 'staff' AND ua.subject_id = s.id " +
            "LEFT JOIN staff_role sr ON sr.staff_id = s.id AND sr.revoked_at IS NULL " +
            "  AND sr.scope_type = 'school' AND sr.scope_id = ? " +
            "WHERE s.school_id = ? " +
            "GROUP BY s.id, ua.id, s.first_name, s.last_name, s.email " +
            "ORDER BY s.first_name",
            (rs, i) -> new StaffWithRolesDto(
                UUID.fromString(rs.getString("staff_id")),
                rs.getString("user_account_id") == null ? null : UUID.fromString(rs.getString("user_account_id")),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("email"),
                readTextArray(rs, "role_codes")
            ),
            schoolId, schoolId
        );
    }

    public void assignRole(UUID staffId, UUID schoolId, String roleCode) {
        jdbc.update(
            "INSERT INTO staff_role (id, staff_id, role_code, scope_type, scope_id) " +
            "VALUES (gen_random_uuid(), ?, ?, 'school', ?) " +
            "ON CONFLICT (staff_id, role_code, scope_type, scope_id) DO UPDATE SET revoked_at = NULL",
            staffId, roleCode, schoolId
        );
    }

    public void unassignRole(UUID staffId, UUID schoolId, String roleCode) {
        jdbc.update(
            "UPDATE staff_role SET revoked_at = now() " +
            "WHERE staff_id = ? AND role_code = ? AND scope_type = 'school' AND scope_id = ? AND revoked_at IS NULL",
            staffId, roleCode, schoolId
        );
    }
}
