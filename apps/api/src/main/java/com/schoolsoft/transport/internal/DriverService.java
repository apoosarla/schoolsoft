package com.schoolsoft.transport.internal;

import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.iam.api.AccountProvisioning;
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
 * Adding a driver, and — when the driver is linked to a staff record — giving
 * that person the way into the driver app (BUG-18).
 *
 * <p>{@code driver.staff_id} is what {@code RouteScope} walks to find a
 * driver's routes, but the link alone opened nothing: without the
 * {@code driver} role the staff member held no {@code transport.drive}, so the
 * app refused them. The grant now happens in the same transaction as the link,
 * so a linked driver who cannot sign in no longer exists.</p>
 *
 * <p>Because linking grants a role, it is refused for a staff member who
 * already holds some other one. Otherwise picking the wrong name from the
 * search — which is how the dev data ended up with a driver linked to the
 * principal — quietly hands that person a second role, and the rostered
 * routes go to their login.</p>
 */
@Service
public class DriverService {

    private final TransportRepository repo;
    private final JdbcTemplate jdbc;
    private final AccountProvisioning accounts;
    private final AuditService audit;

    public DriverService(TransportRepository repo, JdbcTemplate jdbc, AccountProvisioning accounts,
                         AuditService audit) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.audit = audit;
    }

    @Transactional
    public DriverDto createDriver(UUID schoolId, UUID staffId, String name, String phone, String licenseNo) {
        if (staffId != null) requireLinkable(schoolId, staffId);
        DriverDto driver = repo.createDriver(schoolId, staffId, name, phone, licenseNo);
        if (staffId != null) {
            accounts.grantSchoolRole(staffId, schoolId, "driver");
            audit.record("transport.driver.link", "driver", driver.id(), null,
                Map.of("staffId", staffId.toString(), "roleGranted", "driver"));
        }
        return driver;
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
