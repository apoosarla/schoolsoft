package com.schoolsoft.platform.db;

import com.schoolsoft.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-aware migration runner. Per design §5a:
 *  - Platform schema migrations run once via spring.flyway.* in application.yml.
 *  - Each chain_X schema gets the migrations under classpath:db/migration/chain
 *    applied independently. Per-schema version tracked in
 *    {@code platform.chain_schema_version}.
 *
 * Runs on application startup when {@code schoolsoft.chain-migrations.auto-apply-on-startup}
 * is true. Production deployments should disable this and use the
 * {@link #migrateChain(UUID)} hook from the deploy pipeline so failures are
 * visible and retryable (Risk R11).
 */
@Component
public class ChainSchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(ChainSchemaMigrator.class);

    private final DataSource dataSource;
    private final JdbcTemplate platformJdbc;
    private final boolean autoApply;
    private final String migrationsLocation;

    public ChainSchemaMigrator(
            DataSource dataSource,
            JdbcTemplate platformJdbc,
            @Value("${schoolsoft.chain-migrations.auto-apply-on-startup:true}") boolean autoApply,
            @Value("${schoolsoft.chain-migrations.locations:classpath:db/migration/chain}") String migrationsLocation
    ) {
        this.dataSource = dataSource;
        this.platformJdbc = platformJdbc;
        this.autoApply = autoApply;
        this.migrationsLocation = migrationsLocation;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateAllChainsOnStartup() {
        if (!autoApply) {
            log.info("Chain migrations auto-apply disabled; skipping.");
            return;
        }
        TenantContext.set(TenantContext.platformAdmin(null));
        try {
            List<ChainRow> chains = platformJdbc.query(
                "SELECT id, schema_name FROM platform.chain WHERE status = 'active'",
                (rs, i) -> new ChainRow(UUID.fromString(rs.getString("id")), rs.getString("schema_name"))
            );
            log.info("Applying chain migrations to {} chain(s)", chains.size());
            for (ChainRow c : chains) {
                migrateChain(c.id, c.schemaName);
            }
        } finally {
            TenantContext.clear();
        }
    }

    public void migrateChain(UUID chainId) {
        TenantContext.set(TenantContext.platformAdmin(null));
        try {
            String schema = platformJdbc.queryForObject(
                "SELECT schema_name FROM platform.chain WHERE id = ?", String.class, chainId);
            migrateChain(chainId, schema);
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    public void migrateChain(UUID chainId, String schemaName) {
        log.info("Migrating chain schema '{}'", schemaName);
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schemaName)
                    .defaultSchema(schemaName)
                    .locations(migrationsLocation)
                    .createSchemas(true)
                    .baselineOnMigrate(true)
                    .load();
            flyway.migrate();
            // Not result.targetSchemaVersion — Flyway only populates that when migrate()
            // actually applied something. On a no-op run (schema already current, which is
            // every restart after the first) it's null/empty, and treating that as "reset
            // to 0" was overwriting the real tracked version. flyway.info().current() reports
            // the schema's actual current version regardless of whether this call did anything.
            var current = flyway.info().current();
            int version = (current == null) ? 0 : Integer.parseInt(current.getVersion().getVersion());
            platformJdbc.update(
                "INSERT INTO platform.chain_schema_version (chain_id, schema_version, last_migrated_at, last_error) " +
                "VALUES (?, ?, now(), NULL) " +
                "ON CONFLICT (chain_id) DO UPDATE SET schema_version = EXCLUDED.schema_version, " +
                "  last_migrated_at = EXCLUDED.last_migrated_at, last_error = NULL",
                chainId, version
            );
            platformJdbc.update(
                "UPDATE platform.chain SET schema_version = ?, updated_at = now() WHERE id = ?",
                version, chainId
            );
        } catch (RuntimeException ex) {
            log.error("Migration failed for schema {}: {}", schemaName, ex.getMessage(), ex);
            platformJdbc.update(
                "INSERT INTO platform.chain_schema_version (chain_id, schema_version, last_migrated_at, last_error) " +
                "VALUES (?, -1, now(), ?) " +
                "ON CONFLICT (chain_id) DO UPDATE SET last_error = EXCLUDED.last_error, last_migrated_at = now()",
                chainId, ex.getMessage()
            );
            throw ex;
        }
    }

    private record ChainRow(UUID id, String schemaName) {}
}
