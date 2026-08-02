package com.schoolsoft.jobs.internal;

import com.schoolsoft.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the chain-scoped outbox table per active chain. Stub publisher in
 * MVP — writes log lines + marks rows published. Phase 2 pushes to Kafka.
 *
 * Per §3 principle 4 + R11: idempotent on outbox.id; per-chain loop runs in a
 * trusted tenant context so RLS does not filter out internal-system rows.
 */
@Component
@EnableScheduling
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final DataSource dataSource;
    private final JdbcTemplate platformJdbc;

    public OutboxPublisher(DataSource dataSource, JdbcTemplate platformJdbc) {
        this.dataSource = dataSource;
        this.platformJdbc = platformJdbc;
    }

    @Scheduled(fixedDelayString = "${schoolsoft.jobs.outbox.delay-ms:5000}")
    public void drainAll() {
        List<ChainRow> chains = platformJdbc.query(
            "SELECT id, schema_name FROM platform.chain WHERE status='active'",
            (rs, i) -> new ChainRow(UUID.fromString(rs.getString("id")), rs.getString("schema_name"))
        );
        for (ChainRow c : chains) {
            try { drainChain(c); }
            catch (Exception ex) { log.error("Outbox drain failed for {}: {}", c.schemaName, ex.getMessage()); }
        }
    }

    private void drainChain(ChainRow c) {
        TenantContext.set(TenantContext.trustedJob(c.schemaName, c.id));
        try {
            var jdbc = new JdbcTemplate(dataSource);
            var rows = jdbc.queryForList(
                "SELECT id, aggregate_type, aggregate_id, event_type, payload " +
                "FROM outbox WHERE published_at IS NULL ORDER BY occurred_at LIMIT 100"
            );
            for (var row : rows) {
                UUID id = UUID.fromString(row.get("id").toString());
                log.debug("[outbox/{}] publish {} {} {}", c.schemaName,
                    row.get("aggregate_type"), row.get("event_type"), id);
                jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ?", id);
            }
        } finally {
            TenantContext.clear();
        }
    }

    private record ChainRow(UUID id, String schemaName) {}
}
