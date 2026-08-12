package com.schoolsoft.jobs.api;

import com.schoolsoft.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Runs background work across every tenant, one school at a time.
 *
 * Until Phase 4 the only scheduled job was the outbox drainer, which needed
 * none of this. Invoice generation, dunning and late fees do: they write
 * money, they run on every school in every chain, and a half-finished run has
 * to be safe to repeat. So each unit of work here gets
 *
 * <ul>
 *   <li>a <b>per-school advisory lock</b>, so two application instances (or a
 *       scheduled run racing a manual one) cannot bill the same school twice;</li>
 *   <li>a <b>run row</b> keyed on (school, job, run key) — the same key twice is
 *       one run, which is what makes a retry after a crash a no-op rather than a
 *       second set of invoices;</li>
 *   <li><b>isolation between schools</b>: one school's failure is logged against
 *       that school's run and the loop carries on.</li>
 * </ul>
 */
@Service
public class TenantJobRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantJobRunner.class);

    private final DataSource dataSource;
    private final JdbcTemplate platformJdbc;

    public TenantJobRunner(DataSource dataSource, JdbcTemplate platformJdbc) {
        this.dataSource = dataSource;
        this.platformJdbc = platformJdbc;
    }

    public record SchoolRef(UUID chainId, String chainSchema, UUID schoolId) {}

    /** Outcome of one school's unit of work: what it did, for the run's stats. */
    public record Outcome(Map<String, Object> stats) {
        public static Outcome of(Object... keyValuePairs) {
            var map = new java.util.LinkedHashMap<String, Object>();
            for (int i = 0; i < keyValuePairs.length; i += 2) {
                map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
            }
            return new Outcome(map);
        }
    }

    /**
     * Runs {@code work} for every active school of every active chain. The work
     * receives a tenant-bound {@link JdbcTemplate} and runs inside a trusted
     * context, so row-level security does not hide the rows a system job is
     * meant to see.
     */
    public void forEachSchool(String jobName, String runKey, Function<SchoolRef, Outcome> work) {
        for (ChainRow chain : activeChains()) {
            TenantContext.set(TenantContext.trustedJob(chain.schemaName(), chain.id()));
            try {
                var jdbc = new JdbcTemplate(dataSource);
                List<UUID> schools = jdbc.query("SELECT id FROM school WHERE is_active ORDER BY name",
                    (rs, i) -> UUID.fromString(rs.getString("id")));
                for (UUID schoolId : schools) {
                    runForSchool(jdbc, new SchoolRef(chain.id(), chain.schemaName(), schoolId),
                        jobName, runKey, work);
                }
            } catch (Exception e) {
                log.error("Job {} failed for chain {}: {}", jobName, chain.schemaName(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    /**
     * One school's slice. Returns false when the run was skipped — either
     * another worker holds the lock, or this run key already completed.
     */
    public boolean runForSchool(JdbcTemplate jdbc, SchoolRef school, String jobName, String runKey,
                                Function<SchoolRef, Outcome> work) {
        Boolean locked = jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(hashtext(?), hashtext(?))",
            Boolean.class, jobName, school.schoolId().toString());
        if (locked == null || !locked) {
            log.debug("Job {} already running for school {}", jobName, school.schoolId());
            return false;
        }

        Integer done = jdbc.queryForObject(
            "SELECT count(*) FROM job_run WHERE school_id = ? AND job_name = ? AND run_key = ? " +
            "  AND state = 'completed'",
            Integer.class, school.schoolId(), jobName, runKey);
        if (done != null && done > 0) {
            log.debug("Job {} run {} already completed for school {}", jobName, runKey, school.schoolId());
            return false;
        }

        UUID runId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO job_run (id, school_id, job_name, run_key, state) VALUES (?, ?, ?, ?, 'running') " +
            "ON CONFLICT (school_id, job_name, run_key) DO UPDATE SET " +
            "  state = 'running', started_at = now(), finished_at = NULL, error_message = NULL",
            runId, school.schoolId(), jobName, runKey);

        try {
            Outcome outcome = work.apply(school);
            jdbc.update(
                "UPDATE job_run SET state = 'completed', finished_at = now(), stats = ?::jsonb " +
                "WHERE school_id = ? AND job_name = ? AND run_key = ?",
                toJson(outcome.stats()), school.schoolId(), jobName, runKey);
            return true;
        } catch (Exception e) {
            log.error("Job {} failed for school {}: {}", jobName, school.schoolId(), e.getMessage(), e);
            jdbc.update(
                "UPDATE job_run SET state = 'failed', finished_at = now(), error_message = ? " +
                "WHERE school_id = ? AND job_name = ? AND run_key = ?",
                e.getMessage(), school.schoolId(), jobName, runKey);
            return false;
        }
    }

    private List<ChainRow> activeChains() {
        return platformJdbc.query(
            "SELECT id, schema_name FROM platform.chain WHERE status = 'active'",
            (rs, i) -> new ChainRow(UUID.fromString(rs.getString("id")), rs.getString("schema_name")));
    }

    private String toJson(Map<String, Object> stats) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : stats.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) sb.append(value);
            else sb.append('"').append(String.valueOf(value).replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    private record ChainRow(UUID id, String schemaName) {}
}
