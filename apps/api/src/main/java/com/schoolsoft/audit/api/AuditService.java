package com.schoolsoft.audit.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(String action, String targetType, UUID targetId,
                       Object beforeState, Object afterState) {
        record(action, targetType, targetId, beforeState, afterState, null, null);
    }

    /**
     * The full entry: what changed, and why it was allowed to (SEC-08). The
     * reason is a first-class column rather than a note inside the payload
     * because it is the column an auditor reads.
     */
    public void record(String action, String targetType, UUID targetId,
                       Object beforeState, Object afterState, String reason, Object requestPayload) {
        var snap = TenantContext.get();
        UUID userId = snap == null ? null : snap.userAccountId();
        UUID schoolId = snap == null ? null : snap.schoolId();
        String ip = null, ua = null;
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            ip = sra.getRequest().getRemoteAddr();
            ua = sra.getRequest().getHeader("User-Agent");
        }
        try {
            jdbc.update(
                "INSERT INTO audit_log (school_id, actor_user_id, action, target_type, target_id, " +
                "                       before_state, after_state, reason, request_payload, " +
                "                       ip_address, user_agent) " +
                "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::inet, ?)",
                schoolId, userId, action, targetType, targetId,
                beforeState == null ? null : json.writeValueAsString(beforeState),
                afterState  == null ? null : json.writeValueAsString(afterState),
                reason,
                requestPayload == null ? null : json.writeValueAsString(requestPayload),
                ip, ua
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private PGobject jsonb(String s) throws java.sql.SQLException {
        PGobject p = new PGobject();
        p.setType("jsonb");
        p.setValue(s);
        return p;
    }

    public List<AuditLogEntryDto> query(String targetType, UUID targetId, UUID actorUserId, int limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT id, school_id, actor_user_id, action, target_type, target_id, reason, occurred_at " +
            "FROM audit_log WHERE 1=1 "
        );
        List<Object> args = new ArrayList<>();
        if (targetType != null) { sql.append("AND target_type = ? "); args.add(targetType); }
        if (targetId != null) { sql.append("AND target_id = ? "); args.add(targetId); }
        if (actorUserId != null) { sql.append("AND actor_user_id = ? "); args.add(actorUserId); }
        sql.append("ORDER BY occurred_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(),
            (rs, i) -> new AuditLogEntryDto(
                rs.getLong("id"),
                rs.getString("school_id") == null ? null : UUID.fromString(rs.getString("school_id")),
                rs.getString("actor_user_id") == null ? null : UUID.fromString(rs.getString("actor_user_id")),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id") == null ? null : UUID.fromString(rs.getString("target_id")),
                rs.getString("reason"),
                rs.getTimestamp("occurred_at").toInstant()
            ),
            args.toArray()
        );
    }
}
