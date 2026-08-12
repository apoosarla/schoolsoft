package com.schoolsoft.audit.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads a row as JSON so an audit entry can carry the whole before and after
 * state rather than the two fields whoever wrote the call happened to think of.
 */
@Service
public class AuditSnapshots {

    private static final Logger log = LoggerFactory.getLogger(AuditSnapshots.class);

    /** Table names come from annotation constants; the check is belt and braces. */
    private static final Pattern SAFE_TABLE = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public AuditSnapshots(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** The row as JSON, or null when the table, the id or the row is absent. */
    public JsonNode of(String table, UUID id) {
        if (table == null || id == null || !SAFE_TABLE.matcher(table).matches()) return null;
        try {
            var rows = jdbc.query(
                "SELECT to_jsonb(t) AS row FROM " + table + " t WHERE t.id = ?",
                (rs, i) -> rs.getString("row"), id);
            return rows.isEmpty() ? null : json.readTree(rows.get(0));
        } catch (Exception e) {
            // An audit snapshot must never be the reason a legitimate mutation
            // fails; the entry is still written, just without this half.
            log.warn("Audit snapshot failed for {} {}: {}", table, id, e.toString());
            return null;
        }
    }
}
