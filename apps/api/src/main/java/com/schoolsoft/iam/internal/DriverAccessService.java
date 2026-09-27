package com.schoolsoft.iam.internal;

import com.schoolsoft.iam.api.DriverAccess;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The published half of {@link DriverAccess}. */
@Service
public class DriverAccessService implements DriverAccess {

    private static final String DRIVER = "driver";

    private final RoleRepository roles;

    public DriverAccessService(RoleRepository roles) {
        this.roles = roles;
    }

    @Override
    public void grantDriver(UUID staffId, UUID schoolId) {
        roles.assignRole(staffId, schoolId, DRIVER, "school", schoolId);
    }

    @Override
    public void revokeDriver(UUID staffId, UUID schoolId) {
        roles.unassignRole(staffId, schoolId, DRIVER, "school", schoolId);
    }
}
