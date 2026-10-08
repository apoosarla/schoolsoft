package com.schoolsoft.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The trail of what Schoolsoft's own operators did (SEC-06).
 *
 * <p>{@link AuditService} writes into the chain the caller stands in. A
 * platform admin stands in none: their token names the {@code platform}
 * schema, which holds no {@code audit_log}, so the widest reach in the system
 * was the one nobody could be asked about. This is the other log —
 * {@code platform.operator_audit_log} (V007), one row per request an operator
 * made, read or write, allowed or refused.</p>
 *
 * <p>Nothing calls {@link #record} to opt in. {@code OperatorAuditInterceptor}
 * writes the row for every request made as a {@code platform_admin}, so a new
 * operator endpoint is audited by existing. A handler only says which chain
 * it reached into ({@link #about}), because that is the one thing the
 * interceptor cannot always read off the path.</p>
 */
@Service
public class OperatorTrail {

    private static final String ATTR_CHAIN = "schoolsoft.operator.chainId";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public OperatorTrail(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Names the chain the current request is about, for its row in the trail.
     * A no-op outside a request — a job that provisions a chain is not an
     * operator's act.
     */
    public static void about(UUID chainId) {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null && chainId != null) {
            attrs.setAttribute(ATTR_CHAIN, chainId, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** The chain {@link #about} named on this request, if any handler did. */
    public static UUID chainOf(HttpServletRequest request) {
        return (UUID) request.getAttribute(ATTR_CHAIN);
    }

    /**
     * Appends one row. The table is always named in full: by the time this
     * runs the request may have stepped into a chain's schema and out again,
     * and the row belongs to the platform whichever schema the connection
     * happens to be looking at.
     */
    public void record(UUID actorUserId, String action, String path, UUID chainId,
                       JsonNode requestPayload, int status, String ip, String userAgent) {
        jdbc.update(
            "INSERT INTO platform.operator_audit_log "
                + "(actor_user_id, action, path, chain_id, request_payload, status, ip_address, user_agent) "
                + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?::inet, ?)",
            actorUserId, action, path, chainId,
            requestPayload == null ? null : requestPayload.toString(),
            status, ip, userAgent);
    }

    /**
     * An operator came through the door. Recorded by the sign-in itself rather
     * than the interceptor: until the code is verified there is no operator on
     * the request to attribute it to, and its body is a one-time code that has
     * no business being kept.
     */
    public void signedIn(UUID actorUserId, HttpServletRequest request) {
        record(actorUserId, request.getMethod() + " " + request.getRequestURI(), request.getRequestURI(),
            null, null, 200, request.getRemoteAddr(), request.getHeader("User-Agent"));
    }

    public List<OperatorAuditEntryDto> query(UUID chainId, UUID actorUserId, int limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT l.id, l.actor_user_id, u.email AS actor_email, l.action, l.path, l.chain_id, "
                + "       l.request_payload::text AS request_payload, l.status, l.occurred_at "
                + "FROM platform.operator_audit_log l "
                + "JOIN platform.platform_user u ON u.id = l.actor_user_id WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (chainId != null) { sql.append("AND l.chain_id = ? "); args.add(chainId); }
        if (actorUserId != null) { sql.append("AND l.actor_user_id = ? "); args.add(actorUserId); }
        sql.append("ORDER BY l.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(),
            (rs, i) -> new OperatorAuditEntryDto(
                rs.getLong("id"),
                UUID.fromString(rs.getString("actor_user_id")),
                rs.getString("actor_email"),
                rs.getString("action"),
                rs.getString("path"),
                rs.getString("chain_id") == null ? null : UUID.fromString(rs.getString("chain_id")),
                payload(rs.getString("request_payload")),
                rs.getInt("status"),
                rs.getTimestamp("occurred_at").toInstant()),
            args.toArray());
    }

    private JsonNode payload(String raw) {
        if (raw == null) return null;
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("operator_audit_log holds a payload that is not JSON", e);
        }
    }
}
