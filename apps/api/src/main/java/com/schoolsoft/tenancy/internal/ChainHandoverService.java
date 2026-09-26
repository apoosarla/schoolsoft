package com.schoolsoft.tenancy.internal;

import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.iam.api.AccountProvisioning;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.tenancy.api.ChainAdminDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handing a freshly provisioned chain to the customer who bought it.
 *
 * <p>The mirror of {@link SchoolOnboardingService#handOver}, one level up. A
 * school opened by its chain has nobody in it, so its first keyholder is
 * appointed from outside; a chain provisioned by Schoolsoft has nobody in it
 * either, and nothing inside a chain can appoint that first person, because
 * every door into a chain is a {@code user_account} in it. So this one is the
 * operator's, and it is the only act in {@code ChainAdminController} that
 * writes a person into a customer's chain.</p>
 *
 * <p>Everything after it belongs to the customer: the chain admin opens their
 * own schools and appoints each school's first keyholder, and the schools hire
 * everybody else. Which is why the door shuts here the same way it shuts
 * there — a chain gets one HQ account from the vendor, not an HQ staff list.
 * A second one is the customer's own business: {@link ChainHqService}.</p>
 */
@Service
public class ChainHandoverService {

    private static final String ADMINS =
        "SELECT id, email, phone, is_active, created_at FROM user_account "
            + "WHERE subject_type = 'chain_admin' ORDER BY created_at";

    private final JdbcTemplate jdbc;
    private final AccountProvisioning accounts;
    private final AuditService audit;

    public ChainHandoverService(JdbcTemplate jdbc, AccountProvisioning accounts, AuditService audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.audit = audit;
    }

    /**
     * Who runs this chain. Empty means the chain has been provisioned and
     * never handed over — schools can be created in it by the operator, but
     * nobody can appoint a school's first administrator, so its schools cannot
     * leave {@code draft}.
     */
    public List<ChainAdminDto> admins() {
        return jdbc.query(ADMINS, (rs, i) -> {
            String email = rs.getString("email");
            String phone = rs.getString("phone");
            return new ChainAdminDto(
                UUID.fromString(rs.getString("id")), email, phone,
                email == null ? phone : email,
                rs.getBoolean("is_active"),
                rs.getTimestamp("created_at").toInstant());
        });
    }

    /**
     * Appoints the chain's HQ administrator.
     *
     * <p>Recorded in the customer's own {@code audit_log} rather than
     * anywhere on the platform side, because the row that matters is the one
     * the customer can be shown: an account they did not create appeared in
     * their chain, and this says which operator put it there. The actor column
     * is null — the writer is a trusted job standing in a schema that holds no
     * record of the operator — so the operator is named in {@code reason},
     * which is the column an auditor reads.</p>
     *
     * @param operator how to name the operator doing this, for the audit row
     */
    @Transactional
    public ChainAdminDto appoint(String email, String phone, String operator) {
        List<ChainAdminDto> existing = admins();
        if (!existing.isEmpty()) {
            throw new ConflictException(
                "This chain already has an HQ administrator (" + existing.get(0).signsInWith() + "). "
                + "Handing a chain over happens once — anyone else at their HQ is theirs to add.");
        }

        UUID accountId = accounts.createChainAdminAccount(email, phone);
        ChainAdminDto appointed = admins().stream()
            .filter(a -> a.accountId().equals(accountId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Appointed account " + accountId + " did not persist"));

        audit.record("chain.admin_appointed", "user_account", accountId, null,
            Map.of("signsInWith", appointed.signsInWith(), "subjectType", "chain_admin"),
            "Chain handed over by Schoolsoft operator " + operator, null);

        return appointed;
    }
}
