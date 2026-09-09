package com.schoolsoft.notification.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.notification.api.DeliveryStats;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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
            // A public enquiry has no guardian row and no login: the applicant
            // *is* the application, and the contact details they typed into the
            // form are the only ones the school holds. Handing over a phone and
            // an email on an admissions form is consent to be answered on them,
            // so email and SMS are on; push has no device to go to and WhatsApp
            // needs the explicit opt-in §10 requires, which a form does not give.
            case "applicant" -> jdbc.query(
                "SELECT id, guardian_phone, guardian_email FROM admission_application WHERE id = ?",
                rs -> rs.next() ? new Recipient(
                    UUID.fromString(rs.getString("id")), "applicant",
                    rs.getString("guardian_phone"), rs.getString("guardian_email"),
                    false, false, true, true
                ) : null,
                id
            );
            default -> null;
        };
    }

    /**
     * The guardians a school actually writes to about these children —
     * {@code is_communications_recipient} is the flag that keeps a
     * non-custodial parent off the daily traffic without unlinking them.
     * Distinct, because a sibling pair shares a parent and one announcement
     * should reach that parent once.
     */
    public List<UUID> communicationsGuardiansOf(Collection<UUID> studentIds) {
        if (studentIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(studentIds.size(), "?"));
        return jdbc.query(
            "SELECT DISTINCT guardian_id FROM guardian_student " +
            "WHERE student_id IN (" + placeholders + ") AND is_communications_recipient",
            (rs, i) -> UUID.fromString(rs.getString("guardian_id")),
            studentIds.toArray()
        );
    }

    /** The child a notice is about, for the message body. Null when the id is not a student. */
    public String studentName(UUID studentId) {
        var names = jdbc.query(
            "SELECT first_name, last_name FROM student WHERE id = ?",
            (rs, i) -> (rs.getString("first_name") + " "
                + (rs.getString("last_name") == null ? "" : rs.getString("last_name"))).trim(),
            studentId);
        return names.isEmpty() ? null : names.get(0);
    }

    /**
     * Writes the dispatch row, or returns null when this exact event has
     * already been dispatched to this recipient on this channel.
     *
     * <p>The guard is the unique index and {@code ON CONFLICT DO NOTHING}
     * rather than a read followed by an insert: two threads saving the same
     * register at once would both pass a read, and the second send is the
     * thing being prevented.</p>
     */
    public UUID recordDispatch(UUID schoolId, String recipientType, UUID recipientId,
                               String channel, String templateCode, String language,
                               Map<String, Object> variables,
                               String relatedType, UUID relatedId, String dedupeKey) {
        UUID id = UUID.randomUUID();
        int inserted;
        try {
            PGobject varsJson = new PGobject();
            varsJson.setType("jsonb");
            varsJson.setValue(json.writeValueAsString(variables == null ? Map.of() : variables));
            inserted = jdbc.update(
                "INSERT INTO notification_dispatch " +
                "(id, school_id, recipient_type, recipient_id, channel, template_code, language, variables, " +
                " related_type, related_id, dedupe_key) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (dedupe_key, recipient_type, recipient_id, channel) " +
                "  WHERE dedupe_key IS NOT NULL DO NOTHING",
                id, schoolId, recipientType, recipientId, channel, templateCode, language, varsJson,
                relatedType, relatedId, dedupeKey
            );
        } catch (JsonProcessingException | java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return inserted == 0 ? null : id;
    }

    public void markSent(UUID dispatchId, String providerMsgId) {
        jdbc.update(
            "UPDATE notification_dispatch SET status='sent', provider_msg_id=?, sent_at=now() WHERE id = ?",
            providerMsgId, dispatchId
        );
    }

    public void markFailed(UUID dispatchId, String reason) {
        jdbc.update(
            "UPDATE notification_dispatch SET status='failed', failure_reason=? WHERE id = ?",
            reason, dispatchId
        );
    }

    /** Everything that went out about one thing, counted by channel and by status. */
    public DeliveryStats statsFor(String relatedType, UUID relatedId) {
        record Row(String channel, String status, Instant queuedAt, Instant sentAt,
                   String recipientType, UUID recipientId) {}
        List<Row> rows = jdbc.query(
            "SELECT channel, status, queued_at, sent_at, recipient_type, recipient_id " +
            "FROM notification_dispatch WHERE related_type = ? AND related_id = ?",
            (rs, i) -> new Row(
                rs.getString("channel"), rs.getString("status"),
                rs.getTimestamp("queued_at").toInstant(),
                rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
                rs.getString("recipient_type"), UUID.fromString(rs.getString("recipient_id"))),
            relatedType, relatedId
        );

        Map<String, Integer> byChannel = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        var recipients = new java.util.HashSet<String>();
        Instant firstQueued = null;
        Instant lastSent = null;
        for (Row r : rows) {
            byChannel.merge(r.channel(), 1, Integer::sum);
            byStatus.merge(r.status(), 1, Integer::sum);
            recipients.add(r.recipientType() + ":" + r.recipientId());
            if (firstQueued == null || r.queuedAt().isBefore(firstQueued)) firstQueued = r.queuedAt();
            if (r.sentAt() != null && (lastSent == null || r.sentAt().isAfter(lastSent))) lastSent = r.sentAt();
        }
        int sent = byStatus.getOrDefault("sent", 0)
            + byStatus.getOrDefault("delivered", 0)
            + byStatus.getOrDefault("read", 0);
        int failed = byStatus.getOrDefault("failed", 0) + byStatus.getOrDefault("rejected", 0);
        return new DeliveryStats(
            relatedType, relatedId, recipients.size(), rows.size(),
            sent, failed, rows.size() - sent - failed,
            byChannel, byStatus, firstQueued, lastSent,
            firstQueued == null || lastSent == null ? null : lastSent.toEpochMilli() - firstQueued.toEpochMilli()
        );
    }
}
