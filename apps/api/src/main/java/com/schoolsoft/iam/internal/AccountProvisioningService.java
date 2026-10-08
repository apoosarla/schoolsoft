package com.schoolsoft.iam.internal;

import com.schoolsoft.iam.api.AccountProvisioning;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The published half of {@link AccountProvisioning}.
 *
 * <p>The account INSERT names {@code school_id} explicitly and selects the
 * staff row it belongs to, for the reason {@link RoleRepository#assignRole}
 * does: {@code user_account} carries a {@code school_id} and so an RLS policy,
 * but a caller with no school of its own — the chain's HQ — stands outside
 * that policy, and nothing else here would tie the account to the school the
 * staff member actually works at.</p>
 */
@Service
public class AccountProvisioningService implements AccountProvisioning {

    private final JdbcTemplate jdbc;
    private final RoleRepository roles;

    public AccountProvisioningService(JdbcTemplate jdbc, RoleRepository roles) {
        this.jdbc = jdbc;
        this.roles = roles;
    }

    @Override
    public UUID createStaffAccount(UUID schoolId, UUID staffId, String email, String phone) {
        String mail = blankToNull(email);
        String number = blankToNull(phone);
        if (mail == null && number == null) {
            throw new IllegalArgumentException("An account needs an email address or a mobile number to sign in with");
        }
        UUID id = UUID.randomUUID();
        int created = jdbc.update(
            "INSERT INTO user_account (id, school_id, subject_type, subject_id, email, phone) " +
            "SELECT ?, s.school_id, 'staff', s.id, ?, ? FROM staff s WHERE s.id = ? AND s.school_id = ?",
            id, mail, number, staffId, schoolId);
        if (created == 0) {
            throw new IllegalArgumentException("Staff " + staffId + " is not in school " + schoolId);
        }
        return id;
    }

    @Override
    public UUID createChainAdminAccount(String email, String phone) {
        String mail = blankToNull(email);
        String number = blankToNull(phone);
        if (mail == null && number == null) {
            throw new IllegalArgumentException("An account needs an email address or a mobile number to sign in with");
        }
        UUID id = UUID.randomUUID();
        // school_id stays null on purpose, and that null is what makes the
        // account chain-wide: V009's policies read
        // `school_id = current_school_id() OR current_school_id() IS NULL`,
        // so a session with no school sees every school in the chain.
        jdbc.update(
            "INSERT INTO user_account (id, school_id, subject_type, subject_id, email, phone) " +
            "VALUES (?, NULL, 'chain_admin', NULL, ?, ?)",
            id, mail, number);
        return id;
    }

    @Override
    public void syncStaffIdentity(UUID schoolId, UUID staffId, String email, String phone) {
        String mail = blankToNull(email);
        String number = blankToNull(phone);
        if (mail == null && number == null) return;
        int updated = jdbc.update(
            "UPDATE user_account SET email = ?, phone = ? WHERE subject_type = 'staff' AND subject_id = ?",
            mail, number, staffId);
        if (updated == 0) createStaffAccount(schoolId, staffId, mail, number);
    }

    @Override
    public boolean identityTaken(UUID exceptStaffId, String email, String phone) {
        String mail = blankToNull(email);
        String number = blankToNull(phone);
        if (mail == null && number == null) return false;
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM user_account ua WHERE (ua.email = ? OR ua.phone = ?) " +
            "  AND NOT (ua.subject_type = 'staff' AND ua.subject_id IS NOT DISTINCT FROM ?)",
            Integer.class, mail, number, exceptStaffId);
        return n != null && n > 0;
    }

    @Override
    public boolean roleExists(String roleCode) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM role WHERE code = ?", Integer.class, roleCode);
        return n != null && n > 0;
    }

    @Override
    public void grantSchoolRole(UUID staffId, UUID schoolId, String roleCode) {
        roles.assignRole(staffId, schoolId, roleCode, "school", schoolId);
    }

    @Override
    public boolean roleHolds(String roleCode, String permCode) {
        Integer held = jdbc.queryForObject(
            "SELECT count(*) FROM role_perm WHERE role_code = ? AND perm_code = ?",
            Integer.class, roleCode, permCode);
        return held != null && held > 0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
