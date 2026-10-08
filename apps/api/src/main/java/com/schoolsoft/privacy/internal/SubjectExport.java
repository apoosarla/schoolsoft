package com.schoolsoft.privacy.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Everything the school holds on one child, as one document.
 *
 * <p>The tables are not listed. Whatever in the chain's schema carries a
 * {@code student_id} is read, so a module that starts keeping something about
 * a child is in the export the day its migration lands. A list would be right
 * on the day it was written and silently short ever after — and the failure
 * would be an incomplete answer to a statutory request, which nobody notices
 * until somebody is asked to prove otherwise.</p>
 *
 * <p>Rows are handed over as the database holds them. This is the record, not
 * a rendering of it.</p>
 */
@Repository
public class SubjectExport {

    private static final Pattern SAFE_TABLE = Pattern.compile("^[a-z][a-z0-9_]*$");

    private static final String TABLES_ABOUT_A_STUDENT =
        "SELECT table_name FROM information_schema.columns "
            + "WHERE table_schema = current_schema() AND column_name = 'student_id' ORDER BY table_name";

    // Ciphertext is no use to the family and a gateway's raw callback is not
    // theirs: both are dropped from every row rather than table by table.
    private static final String ROW = "(to_jsonb(t) - 'encrypted_payload' - 'raw_payload')::text";

    private static final String STUDENT = "SELECT " + ROW + " FROM student t WHERE t.id = ?";

    private static final String GUARDIANS =
        "SELECT " + ROW + " FROM guardian t JOIN guardian_student gs ON gs.guardian_id = t.id "
            + "WHERE gs.student_id = ? ORDER BY t.created_at";

    private static final String CONSENTS =
        "SELECT " + ROW + " FROM consent_record t "
            + "WHERE t.subject_type = 'student' AND t.subject_id = ? ORDER BY t.granted_at";

    // Hangs off the invoice, not the child, so the sweep below does not find it.
    private static final String PAYMENTS =
        "SELECT " + ROW + " FROM payment t JOIN fee_invoice i ON i.id = t.fee_invoice_id "
            + "WHERE i.student_id = ? ORDER BY t.created_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public SubjectExport(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public JsonNode of(UUID studentId) {
        ObjectNode out = json.createObjectNode();
        out.set("student", rows(STUDENT, studentId).path(0));
        out.set("guardians", rows(GUARDIANS, studentId));
        out.set("consents", rows(CONSENTS, studentId));
        out.set("payments", rows(PAYMENTS, studentId));

        ObjectNode records = out.putObject("records");
        for (String table : jdbc.queryForList(TABLES_ABOUT_A_STUDENT, String.class)) {
            // The name came from the catalogue, not a caller; the check is for
            // the table somebody one day creates with a quoted name.
            if (!SAFE_TABLE.matcher(table).matches()) continue;
            records.set(table, rows("SELECT " + ROW + " FROM " + table + " t WHERE t.student_id = ?", studentId));
        }
        return out;
    }

    private ArrayNode rows(String sql, UUID studentId) {
        ArrayNode array = json.createArrayNode();
        List<String> raw = jdbc.queryForList(sql, String.class, studentId);
        for (String row : raw) {
            try {
                array.add(json.readTree(row));
            } catch (Exception e) {
                throw new IllegalStateException("A row Postgres rendered as JSON did not parse", e);
            }
        }
        return array;
    }
}
