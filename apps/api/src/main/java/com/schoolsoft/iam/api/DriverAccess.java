package com.schoolsoft.iam.api;

import java.util.UUID;

/**
 * The one role {@code transport} may hand out or take back: {@code driver},
 * over the school, for the staff record a driver is linked to (BUG-18).
 *
 * <p>Narrow by construction, as {@link AccountProvisioning} is for
 * {@code tenancy}: linking a driver needs a grant and unlinking one needs a
 * revocation, but neither should let the transport screen reach
 * {@code RoleRepository} and grant or revoke anything else.</p>
 */
public interface DriverAccess {

    /**
     * Grants {@code driver} over the whole school, marked as the link's. A
     * {@code driver} grant somebody already made by hand stays theirs.
     */
    void grantDriver(UUID staffId, UUID schoolId);

    /**
     * Revokes the {@code driver} grant the link made, and only that one: a
     * grant made by hand on the Roles screen outlives the link. True when a
     * grant was revoked.
     */
    boolean revokeDriver(UUID staffId, UUID schoolId);
}
