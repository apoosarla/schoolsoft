package com.schoolsoft.iam.internal;

import com.schoolsoft.iam.api.DriverAccess;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The published half of {@link DriverAccess}. */
@Service
public class DriverAccessService implements DriverAccess {

    private static final String DRIVER = "driver";
    private static final String VIA_LINK = "driver_link";

    private final RoleRepository roles;

    public DriverAccessService(RoleRepository roles) {
        this.roles = roles;
    }

    @Override
    public void grantDriver(UUID staffId, UUID schoolId) {
        roles.assignSchoolRoleVia(staffId, schoolId, DRIVER, VIA_LINK);
    }

    @Override
    public boolean revokeDriver(UUID staffId, UUID schoolId) {
        return roles.unassignSchoolRoleVia(staffId, schoolId, DRIVER, VIA_LINK);
    }
}
