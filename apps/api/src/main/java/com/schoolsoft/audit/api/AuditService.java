package com.schoolsoft.audit.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.platform.tenancy.TenantContext;
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
                "                       before_state, after_state, ip_address, user_agent) " +
                "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::inet, ?)",
                schoolId, userId, action, targetType, targetId,
                beforeState == null ? null : json.writeValueAsString(beforeState),
                afterState  == null ? null : json.writeValueAsString(afterState),
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
}
