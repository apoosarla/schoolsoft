package com.schoolsoft.tenancy.api;

import com.schoolsoft.platform.db.ChainSchemaMigrator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions a new chain (tenant). Per design §5a:
 *   1) Insert into platform.chain.
 *   2) Create the chain_X Postgres schema.
 *   3) Run chain migrations (V001..V00N) against that schema.
 *
 * Idempotent on slug — re-running with the same slug returns the existing
 * chain id and re-attempts any missing migrations (Flyway's job).
 */
@Service
public class ChainProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ChainProvisioningService.class);

    private final JdbcTemplate platformJdbc;
    private final ChainSchemaMigrator migrator;

    public ChainProvisioningService(JdbcTemplate platformJdbc, ChainSchemaMigrator migrator) {
        this.platformJdbc = platformJdbc;
        this.migrator = migrator;
    }

    public record NewChain(UUID id, String schemaName, boolean created) {}

    @Transactional
    public NewChain provision(String slug, String name, String planCode) {
        if (!slug.matches("[a-z][a-z0-9_]{2,40}")) {
            throw new IllegalArgumentException("Chain slug must be lower_snake, 3-40 chars");
        }
        String schemaName = "chain_" + slug;

        var existing = platformJdbc.query(
            "SELECT id, schema_name FROM platform.chain WHERE slug = ?",
            (rs, i) -> new NewChain(UUID.fromString(rs.getString("id")), rs.getString("schema_name"), false),
            slug
        );
        UUID chainId;
        boolean created;
        if (!existing.isEmpty()) {
            chainId = existing.get(0).id;
            created = false;
            log.info("Chain '{}' already exists; running migrations idempotently", slug);
        } else {
            chainId = UUID.randomUUID();
            platformJdbc.update(
                "INSERT INTO platform.chain (id, slug, name, schema_name, plan_code) VALUES (?, ?, ?, ?, ?)",
                chainId, slug, name, schemaName, planCode == null ? "starter" : planCode
            );
            created = true;
        }

        // CREATE SCHEMA is idempotent on IF NOT EXISTS; Flyway will do it too if missing.
        platformJdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
        migrator.migrateChain(chainId, schemaName);

        return new NewChain(chainId, schemaName, created);
    }
}
