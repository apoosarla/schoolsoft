package com.schoolsoft.audit.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Walks the audit log's hash chain and reports the first place it breaks.
 *
 * <p>{@code V027__audit_hash_chain.sql} makes every row carry the hash of the
 * row before it, computed by a database trigger. That makes tampering
 * <em>detectable</em>; this is the thing that detects it. Without a verifier
 * the chain is decoration — nobody reads a hash column by eye.</p>
 *
 * <p>The hash is recomputed here in Java rather than by asking Postgres to
 * check its own work: a verifier that ran the same trigger function it is
 * verifying would report a forged chain as valid, because whoever can rewrite
 * the rows can rewrite the function. The field order and separator below
 * therefore mirror {@code audit_log_payload()} by hand, and
 * {@link #verify()} failing on a freshly written log is the signal that the
 * two have drifted.</p>
 */
@Service
public class AuditChainVerifier {

    /** U+001F, the unit separator — the same joiner {@code audit_log_payload()} uses. */
    private static final String SEP = "\u001F";

    /**
     * Mirrors the trigger's pinned timestamp rendering. Postgres's plain
     * {@code ::text} follows the session's DateStyle and TimeZone, which would
     * make a row hash differently depending on who wrote it.
     */
    private static final String OCCURRED_AT =
        "to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US')";

    private final JdbcTemplate jdbc;

    public AuditChainVerifier(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * @param rowsChecked how far the walk got
     * @param intact      true when every link held
     * @param brokenAtId  the id of the first row that does not verify, or null
     * @param detail      what was wrong with it, or null
     */
    public record Result(long rowsChecked, boolean intact, Long brokenAtId, String detail) {
        public static Result ok(long rows) { return new Result(rows, true, null, null); }
    }

    /**
     * Verifies the whole chain, stopping at the first break — everything after
     * a break is unverifiable anyway, and reporting ten thousand consequent
     * failures buries the one that matters.
     */
    public Result verify() {
        var state = new Object() {
            String previous = null;
            long checked = 0;
            Long brokenAt = null;
            String detail = null;
        };

        jdbc.query(
            "SELECT id, school_id, actor_user_id, action, target_type, target_id, "
          + "       before_state::text AS before_state, after_state::text AS after_state, "
          + "       reason, request_payload::text AS request_payload, "
          + "       " + OCCURRED_AT + " AS occurred_at_text, "
          + "       prev_hash, entry_hash "
          + "FROM audit_log ORDER BY id",
            rs -> {
                if (state.brokenAt != null) return;
                long id = rs.getLong("id");

                String declaredPrev = rs.getString("prev_hash");
                if (!Objects.equals(declaredPrev, state.previous)) {
                    state.brokenAt = id;
                    state.detail = "row " + id + " claims to follow " + describe(declaredPrev)
                        + " but follows " + describe(state.previous)
                        + " — a row before it was removed, or the chain forked";
                    return;
                }

                String payload = String.join(SEP,
                    Long.toString(id),
                    nz(rs.getString("school_id")),
                    nz(rs.getString("actor_user_id")),
                    rs.getString("action"),
                    nz(rs.getString("target_type")),
                    nz(rs.getString("target_id")),
                    nz(rs.getString("before_state")),
                    nz(rs.getString("after_state")),
                    nz(rs.getString("reason")),
                    nz(rs.getString("request_payload")),
                    nz(rs.getString("occurred_at_text")));

                String expected = sha256Hex(nz(declaredPrev) + SEP + payload);
                if (!expected.equals(rs.getString("entry_hash"))) {
                    state.brokenAt = id;
                    state.detail = "row " + id + " does not hash to its recorded value "
                        + "— a field of this row was altered after it was written";
                    return;
                }

                state.previous = rs.getString("entry_hash");
                state.checked++;
            });

        return state.brokenAt == null
            ? Result.ok(state.checked)
            : new Result(state.checked, false, state.brokenAt, state.detail);
    }

    private static String describe(String hash) {
        return hash == null ? "the start of the log" : hash.substring(0, Math.min(12, hash.length()));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not optional", e);
        }
    }
}
