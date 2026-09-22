package com.schoolsoft.iam.api;

import java.util.UUID;

/**
 * Giving somebody a way in, for a caller that cannot use the screens which
 * normally do it.
 *
 * <p>A school's first administrator is the case that needs this: until one
 * exists there is nobody at the school to grant a role or create an account,
 * so the chain's HQ does it while opening the school. Narrow by construction —
 * an account for a staff member and a grant over a school — so {@code tenancy}
 * cannot reach {@code RoleRepository} and mint a role, revoke a grant, or
 * touch a guardian's login.</p>
 */
public interface AccountProvisioning {

    /**
     * Creates the sign-in identity for a staff member. One of {@code email} or
     * {@code phone} must be present; {@code user_account} refuses a row with
     * neither, and a row with neither could never sign in anyway.
     */
    UUID createStaffAccount(UUID schoolId, UUID staffId, String email, String phone);

    /** Grants {@code roleCode} over the whole school. */
    void grantSchoolRole(UUID staffId, UUID schoolId, String roleCode);

    /**
     * Whether a role carries a permission, asked of {@code role_perm} rather
     * than of the role's name — a school that built its own role holding
     * {@code structure.manage} has somebody who can run the place, and the
     * caller checking that should not have to know the three built-in names.
     */
    boolean roleHolds(String roleCode, String permCode);
}
