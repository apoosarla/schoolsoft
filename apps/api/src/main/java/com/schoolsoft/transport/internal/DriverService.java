package com.schoolsoft.transport.internal;

import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.iam.api.DriverAccess;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.transport.api.DriverDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adding a driver, and linking or unlinking the staff record whose login
 * drives with the driver app (BUG-18).
 *
 * <p>{@code driver.staff_id} is what {@code RouteScope} walks to find a
 * driver's routes, but the link alone opened nothing: without the
 * {@code driver} role the staff member held no {@code transport.drive}, so the
 * app refused them. The grant now happens in the same transaction as the link,
 * and the revocation in the same transaction as the unlink, so the role and
 * the link cannot drift apart.</p>
 *
 * <p>Because linking grants a role, it is refused for a staff member who
 * already holds some other one. Otherwise picking the wrong name from the
 * search — which is how the dev data ended up with a driver linked to the
 * principal — quietly hands that person a second role, and the rostered
 * routes go to their login.</p>
 *
 * <p>Link and unlink are each one conditional UPDATE that names the state it
 * moves out of. Re-running one that already happened answers with the driver
 * as it stands rather than failing.</p>
 */
@Service
public class DriverService {

    private final TransportRepository repo;
    private final JdbcTemplate jdbc;
    private final DriverAccess access;
    private final AuditService audit;

    public DriverService(TransportRepository repo, JdbcTemplate jdbc, DriverAccess access, AuditService audit) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public DriverDto createDriver(UUID schoolId, UUID staffId, String name, String phone, String licenseNo) {
        DriverDto driver = repo.createDriver(schoolId, null, name, phone, licenseNo);
        return staffId == null ? driver : link(schoolId, driver.id(), staffId);
    }

    @Transactional
    public DriverDto link(UUID schoolId, UUID driverId, UUID staffId) {
        DriverDto current = require(schoolId, driverId);
        if (staffId.equals(current.staffId())) return current;
        if (current.staffId() != null) {
            throw new ConflictException(current.name() + " is already linked to another staff record; unlink it first");
        }
        requireLinkable(schoolId, staffId);

        int moved = jdbc.update(
            "UPDATE driver SET staff_id = ? WHERE id = ? AND school_id = ? AND staff_id IS NULL",
            staffId, driverId, schoolId);
        if (moved == 0) throw new ConflictException(current.name() + " was linked by somebody else just now");

        access.grantDriver(staffId, schoolId);
        audit.record("transport.driver.link", "driver", driverId, null,
            Map.of("staffId", staffId.toString(), "roleGranted", "driver"));
        return require(schoolId, driverId);
    }

    /**
     * Unlinks the driver and takes back the {@code driver} role the link
     * granted, unless another driver row still links them. A {@code driver}
     * grant made by hand on the Roles screen is not the link's and stays. The driver
     * row stays — its trips and route history still resolve — and so do its
     * route assignments, which stop reaching anybody's login until the driver
     * is linked again.
     */
    @Transactional
    public DriverDto unlink(UUID schoolId, UUID driverId) {
        DriverDto current = require(schoolId, driverId);
        List<UUID> was = jdbc.query(
            "UPDATE driver d SET staff_id = NULL FROM driver old " +
            "WHERE d.id = old.id AND d.id = ? AND d.school_id = ? AND d.staff_id IS NOT NULL " +
            "RETURNING old.staff_id",
            (rs, i) -> UUID.fromString(rs.getString(1)), driverId, schoolId);
        if (was.isEmpty()) return current;
        UUID staffId = was.get(0);

        Integer stillLinked = jdbc.queryForObject(
            "SELECT count(*) FROM driver WHERE school_id = ? AND staff_id = ?", Integer.class, schoolId, staffId);
        boolean revoked = (stillLinked == null || stillLinked == 0) && access.revokeDriver(staffId, schoolId);

        audit.record("transport.driver.unlink", "driver", driverId,
            Map.of("staffId", staffId.toString()), Map.of("roleRevoked", revoked));
        return require(schoolId, driverId);
    }

    private DriverDto require(UUID schoolId, UUID driverId) {
        return repo.findDriver(schoolId, driverId)
            .orElseThrow(() -> new NotFoundException("Driver " + driverId + " is not in school " + schoolId));
    }

    private void requireLinkable(UUID schoolId, UUID staffId) {
        var staff = jdbc.query(
            "SELECT trim(first_name || ' ' || coalesce(last_name, '')) FROM staff WHERE id = ? AND school_id = ?",
            (rs, i) -> rs.getString(1), staffId, schoolId);
        if (staff.isEmpty()) throw new NotFoundException("Staff " + staffId + " is not in school " + schoolId);
        String who = staff.get(0);

        List<String> otherRoles = jdbc.queryForList(
            "SELECT DISTINCT role_code FROM staff_role WHERE staff_id = ? AND revoked_at IS NULL " +
            "  AND role_code <> 'driver' ORDER BY role_code",
            String.class, staffId);
        if (!otherRoles.isEmpty()) {
            throw new ConflictException(who + " already holds " + String.join(", ", otherRoles)
                + ". A driver links to their own staff record, which holds no other role.");
        }

        Integer linked = jdbc.queryForObject(
            "SELECT count(*) FROM driver WHERE school_id = ? AND staff_id = ?", Integer.class, schoolId, staffId);
        if (linked != null && linked > 0) {
            throw new ConflictException(who + " is already linked to a driver");
        }
    }
}
