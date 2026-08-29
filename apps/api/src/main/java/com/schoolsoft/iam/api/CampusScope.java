package com.schoolsoft.iam.api;

import com.schoolsoft.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Which campuses the caller is confined to — a filter, not a decision.
 *
 * <p>Split out of {@link Authz} because the two are different kinds of thing
 * and belong in different places. {@code Authz.rolesOfCurrentUser()} answers
 * "may they", and an answer of "no" is a refusal; that decision belongs at the
 * use case, where somebody auditing access control will look for it. This
 * answers "of what", and its result is a {@code WHERE} clause — which belongs
 * in the query, because a list read that forgets to narrow itself is a leak,
 * and the safest place to make it impossible to forget is next to the SQL.</p>
 *
 * <p>An empty list means <em>unrestricted</em>, not "nothing": school-wide
 * staff, chain admins and platform admins are not campus-bound. A caller with
 * a school-wide grant outranks any campus grant they also hold (GAP-24).</p>
 */
@Service
public class CampusScope {

    private final DataSource dataSource;

    public CampusScope(DataSource dataSource) { this.dataSource = dataSource; }

    /** The campus ids to filter on, or empty when the caller is not campus-bound. */
    public List<UUID> ofCurrentUser() {
        var snap = TenantContext.get();
        if (snap == null || !"staff".equals(snap.subjectType())) return List.of();

        var jdbc = new JdbcTemplate(dataSource);
        Integer schoolWide = jdbc.queryForObject(
            "SELECT count(*) FROM staff_role sr " +
            "JOIN user_account ua ON ua.subject_id = sr.staff_id AND ua.subject_type = 'staff' " +
            "WHERE ua.id = ? AND sr.scope_type = 'school' AND sr.revoked_at IS NULL",
            Integer.class, snap.userAccountId());
        if (schoolWide != null && schoolWide > 0) return List.of();

        return jdbc.query(
            "SELECT DISTINCT sr.scope_id FROM staff_role sr " +
            "JOIN user_account ua ON ua.subject_id = sr.staff_id AND ua.subject_type = 'staff' " +
            "WHERE ua.id = ? AND sr.scope_type = 'campus' AND sr.revoked_at IS NULL",
            (rs, i) -> UUID.fromString(rs.getString("scope_id")), snap.userAccountId());
    }
}
