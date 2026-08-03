package com.schoolsoft.iam.internal;

import com.schoolsoft.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves an account identifier (email or phone) to a chain + school by
 * scanning every active chain's schema. Tolerable at MVP scale (≤ 20 chains);
 * Phase 2 replaces this with a {@code platform.identity_index} table populated
 * by an account-created event, so lookup is O(1).
 *
 * The chain_slug hint, if supplied, narrows the scan to a single schema.
 *
 * Deliberately NOT {@code @Transactional}: {@link TenantAwareDataSource}
 * only re-evaluates {@code search_path} when a connection is freshly
 * acquired from the pool. A surrounding Spring transaction binds one
 * connection for the whole method up front — before this class gets a
 * chance to set {@link TenantContext} per chain — so every query inside
 * would run against whatever schema was current at method entry (the
 * public {@code /v1/auth/*} endpoints that call this have no
 * {@link TenantContext} set at all, so that's the {@code platform}
 * fallback schema, which has no {@code user_account} table). This silently
 * broke every staff/guardian OTP login and refresh-token call until an
 * end-to-end browser test caught it.
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
