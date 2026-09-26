package com.schoolsoft.tenancy.internal;

import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.iam.api.AccountProvisioning;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.ChainAdminDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A chain's HQ growing and shrinking its own membership (TEN-18).
 *
 * <p>Schoolsoft hands a chain over once, to one address
 * ({@link ChainHandoverService}). Everyone after that is the customer's own
 * business, and until this existed a chain whose single HQ address stopped
 * working — somebody left, a mailbox was retired — needed Schoolsoft to run
 * SQL against the customer's schema.</p>
 *
 * <h2>Only a chain admin, whatever the grants say</h2>
 * {@code chain.admin.manage} is in the chain admin's baseline and in no
 * migration, but a school's custom role can name any permission in the
 * vocabulary. A staff member holding it would be a school's employee minting
 * accounts that see every school in the chain. So the subject type is checked
 * here too, and a staff grant of it opens nothing.
 *
 * <h2>Never the last one</h2>
 * Deactivating the chain's last active HQ account locks the customer out of
 * their own chain, which is the problem this service exists to remove. The
 * check and the write run under a row lock on every active HQ account, so two
 * admins deactivating each other at the same moment cannot both succeed and
 * leave nobody.
 */
@Service
public class ChainHqService {

    private final JdbcTemplate jdbc;
    private final AccountProvisioning accounts;
    private final ChainHandoverService handover;
    private final AuditService audit;

    public ChainHqService(JdbcTemplate jdbc, AccountProvisioning accounts,
                          ChainHandoverService handover, AuditService audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.handover = handover;
        this.audit = audit;
    }

    public List<ChainAdminDto> admins() {
        requireChainAdmin();
        return handover.admins();
    }

    /**
     * Adds another HQ administrator. Recorded here rather than by
     * {@code @Audited}, so the audit row names the account that was created
     * rather than nothing — a creation has no id in its path.
     */
    @Transactional
    public ChainAdminDto add(String email, String phone) {
        requireChainAdmin();
        String mail = blankToNull(email);
        String number = blankToNull(phone);
        // Told here rather than left to the UNIQUE constraint, so the reader
        // learns the address is already in use in this chain — possibly by a
        // parent or a teacher — instead of reading a constraint's name.
        Integer taken = jdbc.queryForObject(
            "SELECT count(*) FROM user_account WHERE email = ? OR phone = ?",
            Integer.class, mail, number);
        if (taken != null && taken > 0) {
            throw new ConflictException(
                "That address already signs in to this chain. An HQ account needs one of its own.");
        }

        UUID accountId = accounts.createChainAdminAccount(mail, number);
        ChainAdminDto added = find(accountId);
        audit.record("chain.admin_added", "user_account", accountId, null,
            Map.of("signsInWith", added.signsInWith(), "subjectType", "chain_admin"),
            null, null);
        return added;
    }

    /**
     * Stops an HQ account signing in. Their current access token lives out
     * its short expiry; refresh and sign-in both ask for {@code is_active},
     * so nothing after that works.
     *
     * <p>Deactivating one already inactive answers with it as it is: a retry
     * after a dropped response must not fail because the first one worked.</p>
     */
    @Transactional
    public ChainAdminDto deactivate(UUID accountId) {
        requireChainAdmin();
        List<UUID> active = jdbc.queryForList(
            "SELECT id FROM user_account WHERE subject_type = 'chain_admin' AND is_active "
                + "ORDER BY id FOR UPDATE",
            UUID.class);

        ChainAdminDto target = find(accountId);
        if (!target.active()) return target;
        if (active.size() <= 1) {
            throw new ConflictException(
                "This is the chain's last active HQ administrator. Add another before "
                + "deactivating this one, or nobody will be able to sign in to the chain.");
        }

        int updated = jdbc.update(
            "UPDATE user_account SET is_active = FALSE "
                + "WHERE id = ? AND subject_type = 'chain_admin' AND is_active",
            accountId);
        if (updated == 0) {
            throw new ConflictException("That HQ account changed while this was being saved. Reload and try again.");
        }
        return find(accountId);
    }

    private ChainAdminDto find(UUID accountId) {
        return handover.admins().stream()
            .filter(a -> a.accountId().equals(accountId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("No HQ administrator " + accountId + " in this chain"));
    }

    private static void requireChainAdmin() {
        var snap = TenantContext.get();
        if (snap == null || !"chain_admin".equals(snap.subjectType())) {
            throw new ForbiddenException("Only the chain's own HQ can manage its HQ accounts.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
