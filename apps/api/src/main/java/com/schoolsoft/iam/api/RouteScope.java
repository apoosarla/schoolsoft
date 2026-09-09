package com.schoolsoft.iam.api;

import com.schoolsoft.platform.security.Perm;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ForbiddenException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Which routes a driver is confined to — the transport half of the same axis
 * {@link TeacherScope} covers for teaching, and the last of GAP-31's
 * neighbours.
 *
 * <p>The driver role was granted school-wide {@code student.view} because the
 * driver app had no other way to put a name against a check-in: the route
 * roster returned student ids, so the app read each rider out of
 * {@code /v1/people/students/&#123;id&#125;} one at a time. That is a whole
 * school's student directory handed to a bus. The roster now carries the names
 * it needs (see {@code RouteRiderDto}), the grant is gone, and this class is
 * what keeps the roster itself from being every route's.</p>
 *
 * <h2>Who is confined</h2>
 * Derived from grants rather than role names, exactly as {@link TeacherScope}
 * does, so a school's custom role lands on the right side without a deploy:
 *
 * <blockquote>A staff caller is route-confined when they hold
 * {@code transport.drive} and do <em>not</em> hold
 * {@code transport.manage}.</blockquote>
 *
 * <p>{@code transport.manage} is the marker for standing above the driving
 * layer: whoever assigns vehicles and routes is not themselves confined to
 * one. Principal, vice principal and IT admin hold both and keep the school; a
 * driver holds only the first and is confined. Everybody else — a registrar, an
 * accountant, a parent tracking a bus with {@code transport.track} — holds no
 * driving permission, is never confined, and reads exactly what they read
 * before.</p>
 *
 * <p>A driver with no current {@code route_assignment} is confined to the empty
 * set, not promoted to the school. A missing assignment row shows an empty
 * route picker, which is a work-order for the transport office; the other way
 * round it would be a leak.</p>
 *
 * <h2>Why only the assignment table</h2>
 * The scope is read from {@code route_assignment} alone, and deliberately not
 * from {@code trip}. {@code POST /v1/transport/trips/start} names its own
 * route, so counting a driver's own trips would let any driver mint themselves
 * a route: start a trip on it, then read its roster. Trip start is gated by
 * this scope for the same reason.
 */
@Service
public class RouteScope {

    /**
     * The caller's route confinement. An {@code unrestricted} scope is not an
     * empty one, and conflating the two is how a driver between assignments
     * would get the whole school's riders.
     */
    public record Routes(boolean unrestricted, List<UUID> routeIds) {

        public Routes {
            routeIds = List.copyOf(routeIds);
        }

        static Routes unconfined() {
            return new Routes(true, List.of());
        }

        /** Whether {@code routeId} is inside the scope. */
        public boolean allows(UUID routeId) {
            return unrestricted || (routeId != null && routeIds.contains(routeId));
        }
    }

    private final DataSource dataSource;
    private final PermissionChecker perms;

    public RouteScope(DataSource dataSource, PermissionChecker perms) {
        this.dataSource = dataSource;
        this.perms = perms;
    }

    /** The routes the caller is confined to, or an unrestricted scope. */
    public Routes ofCurrentUser() {
        var snap = TenantContext.get();
        if (snap == null) return Routes.unconfined();
        if (snap.trusted() || "platform_admin".equals(snap.subjectType())) return Routes.unconfined();
        if (!"staff".equals(snap.subjectType())) return Routes.unconfined();
        if (!isConfined()) return Routes.unconfined();

        UUID staffId = currentStaffId(snap);
        if (staffId == null) return new Routes(false, List.of());
        return new Routes(false, routesDrivenBy(staffId));
    }

    /**
     * Asserts the caller may work with {@code routeId} — read its riders, or
     * start a trip on it. A confined caller who is not assigned to it is
     * refused; everybody else passes through untouched.
     */
    public void requireRoute(UUID routeId) {
        var scope = ofCurrentUser();
        if (scope.unrestricted()) return;
        if (routeId == null) throw new ForbiddenException("No route named");
        if (scope.allows(routeId)) return;
        throw new ForbiddenException("You do not drive this route");
    }

    /**
     * Asserts the caller may work with the trip {@code tripId} — checking a
     * student on or off it. Resolved through the trip's route rather than its
     * driver id, because a substitution recorded against the route is still
     * that route's trip.
     */
    public void requireTrip(UUID tripId) {
        var scope = ofCurrentUser();
        if (scope.unrestricted()) return;
        if (tripId == null) throw new ForbiddenException("No trip named");

        var jdbc = new JdbcTemplate(dataSource);
        var routeIds = jdbc.query("SELECT route_id FROM trip WHERE id = ?",
            (rs, i) -> UUID.fromString(rs.getString(1)), tripId);
        // An unknown trip is refused rather than 404'd: a confined caller is
        // not owed the difference between "no such trip" and "not yours".
        if (routeIds.isEmpty() || !scope.allows(routeIds.get(0))) {
            throw new ForbiddenException("You do not drive this route");
        }
    }

    // ===== internals =====

    private boolean isConfined() {
        return perms.holdsUnrestricted(Perm.TRANSPORT_DRIVE)
            && !perms.holdsUnrestricted(Perm.TRANSPORT_MANAGE);
    }

    private UUID currentStaffId(TenantContext.Snapshot snap) {
        if (snap.userAccountId() == null) return null;
        var jdbc = new JdbcTemplate(dataSource);
        var rows = jdbc.query(
            "SELECT subject_id FROM user_account WHERE id = ? AND subject_type = 'staff' AND subject_id IS NOT NULL",
            (rs, i) -> UUID.fromString(rs.getString(1)), snap.userAccountId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Every route this staff member is rostered to drive today. A driver
     * record is linked to a staff record by {@code driver.staff_id}; one person
     * may hold more than one, and an assignment that has not started or has
     * already ended does not count.
     */
    private List<UUID> routesDrivenBy(UUID staffId) {
        var jdbc = new JdbcTemplate(dataSource);
        var today = java.sql.Date.valueOf(LocalDate.now());
        return jdbc.query(
            "SELECT DISTINCT ra.route_id FROM route_assignment ra " +
            "JOIN driver d ON d.id = ra.driver_id " +
            "WHERE d.staff_id = ? AND d.is_active " +
            "  AND ra.effective_from <= ? AND COALESCE(ra.effective_to, 'infinity'::date) >= ?",
            (rs, i) -> UUID.fromString(rs.getString(1)),
            staffId, today, today);
    }
}
