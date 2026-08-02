package com.schoolsoft.iam.internal;

import com.schoolsoft.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an account identifier (email or phone) to a chain + school by
 * scanning every active chain's schema. Tolerable at MVP scale (≤ 20 chains);
 * Phase 2 replaces this with a {@code platform.identity_index} table populated
 * by an account-created event, so lookup is O(1).
 *
 * The chain_slug hint, if supplied, narrows the scan to a single schema.
 */
@Service
public class UserLookupService {

    private final JdbcTemplate platformJdbc;
    private final DataSource dataSource;

    public UserLookupService(JdbcTemplate platformJdbc, DataSource dataSource) {
        this.platformJdbc = platformJdbc;
        this.dataSource = dataSource;
    }

    public record Resolved(
        UUID userAccountId,
        UUID chainId,
        String chainSchema,
        UUID schoolId,
        String subjectType
    ) {}

    @Transactional(readOnly = true)
    public Optional<Resolved> resolve(String identifier, String chainSlug) {
        List<ChainRow> chains;
        if (chainSlug != null && !chainSlug.isBlank()) {
            chains = platformJdbc.query(
                "SELECT id, schema_name FROM platform.chain WHERE slug = ? AND status='active'",
                (rs, i) -> new ChainRow(UUID.fromString(rs.getString("id")), rs.getString("schema_name")),
                chainSlug
            );
        } else {
            chains = platformJdbc.query(
                "SELECT id, schema_name FROM platform.chain WHERE status='active'",
                (rs, i) -> new ChainRow(UUID.fromString(rs.getString("id")), rs.getString("schema_name"))
            );
        }
        for (ChainRow c : chains) {
            Optional<Resolved> hit = lookupInChain(c, identifier);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<Resolved> resolveById(UUID userAccountId, String chainSchema) {
        TenantContext.set(TenantContext.trustedJob(chainSchema, null));
        try {
            var jdbc = new JdbcTemplate(dataSource);
            var rows = jdbc.query(
                "SELECT id, school_id, subject_type FROM user_account WHERE id = ? AND is_active",
                (rs, i) -> new Resolved(
                    UUID.fromString(rs.getString("id")),
                    null,
                    chainSchema,
                    rs.getString("school_id") == null ? null : UUID.fromString(rs.getString("school_id")),
                    rs.getString("subject_type")
                ),
                userAccountId
            );
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } finally {
            TenantContext.clear();
        }
    }

    private Optional<Resolved> lookupInChain(ChainRow chain, String identifier) {
        TenantContext.set(TenantContext.trustedJob(chain.schemaName, chain.id));
        try {
            var jdbc = new JdbcTemplate(dataSource);
            var rows = jdbc.query(
                "SELECT id, school_id, subject_type FROM user_account " +
                "WHERE is_active AND (email = ? OR phone = ?) LIMIT 1",
                (rs, i) -> new Resolved(
                    UUID.fromString(rs.getString("id")),
                    chain.id,
                    chain.schemaName,
                    rs.getString("school_id") == null ? null : UUID.fromString(rs.getString("school_id")),
                    rs.getString("subject_type")
                ),
                identifier, identifier
            );
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } finally {
            TenantContext.clear();
        }
    }

    private record ChainRow(UUID id, String schemaName) {}
}
