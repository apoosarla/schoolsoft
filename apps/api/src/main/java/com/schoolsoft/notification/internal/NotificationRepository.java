package com.schoolsoft.notification.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public NotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Recipient(
        UUID id,
        String type,
        String phone,
        String email,
        boolean optInWhatsapp,
        boolean optInPush,
        boolean optInEmail,
        boolean optInSms
    ) {}

    public Recipient resolveRecipient(String type, UUID id) {
        return switch (type) {
            case "guardian" -> jdbc.query(
                "SELECT id, phone, email, opt_in_whatsapp, opt_in_push, opt_in_email, opt_in_sms FROM guardian WHERE id = ?",
                rs -> rs.next() ? new Recipient(
                    UUID.fromString(rs.getString("id")), "guardian",
                    rs.getString("phone"), rs.getString("email"),
                    rs.getBoolean("opt_in_whatsapp"), rs.getBoolean("opt_in_push"),
                    rs.getBoolean("opt_in_email"), rs.getBoolean("opt_in_sms")
                ) : null,
                id
            );
            case "staff" -> jdbc.query(
                "SELECT id, phone, email FROM staff WHERE id = ?",
                rs -> rs.next() ? new Recipient(
                    UUID.fromString(rs.getString("id")), "staff",
                    rs.getString("phone"), rs.getString("email"),
                    false, true, true, false
                ) : null,
                id
            );
            default -> null;
        };
    }

    public UUID recordDispatch(UUID schoolId, String recipientType, UUID recipientId,
                               String channel, String templateCode, String language,
                               Map<String, Object> variables,
                               String relatedType, UUID relatedId) {
        UUID id = UUID.randomUUID();
        try {
            PGobject varsJson = new PGobject();
            varsJson.setType("jsonb");
            varsJson.setValue(json.writeValueAsString(variables == null ? Map.of() : variables));
            jdbc.update(
                "INSERT INTO notification_dispatch " +
                "(id, school_id, recipient_type, recipient_id, channel, template_code, language, variables, related_type, related_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, schoolId, recipientType, recipientId, channel, templateCode, language, varsJson, relatedType, relatedId
            );
        } catch (JsonProcessingException | java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    public void markSent(UUID dispatchId, String providerMsgId) {
        jdbc.update(
            "UPDATE notification_dispatch SET status='sent', provider_msg_id=?, sent_at=now() WHERE id = ?",
            providerMsgId, dispatchId
        );
    }
}
