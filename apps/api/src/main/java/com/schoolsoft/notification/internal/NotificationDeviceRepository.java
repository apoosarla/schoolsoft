package com.schoolsoft.notification.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationDeviceRepository {

    private final JdbcTemplate jdbc;

    public NotificationDeviceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Device(UUID id, UUID userAccountId, String platform,
                         OffsetDateTime createdAt, OffsetDateTime lastSeenAt) {}

    public record DeviceToken(UUID id, String token, String platform) {}

    public Device upsert(UUID userAccountId, String token, String platform) {
        return jdbc.queryForObject(
            "INSERT INTO notification_device (user_account_id, token, platform) VALUES (?, ?, ?) " +
            "ON CONFLICT (token) DO UPDATE SET user_account_id = EXCLUDED.user_account_id, " +
            "platform = EXCLUDED.platform, last_seen_at = now() " +
            "RETURNING id, user_account_id, platform, created_at, last_seen_at",
            (rs, n) -> new Device(
                rs.getObject("id", UUID.class),
                rs.getObject("user_account_id", UUID.class),
                rs.getString("platform"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("last_seen_at", OffsetDateTime.class)
            ),
            userAccountId, token, platform
        );
    }

    public int delete(UUID id, UUID userAccountId) {
        return jdbc.update("DELETE FROM notification_device WHERE id = ? AND user_account_id = ?", id, userAccountId);
    }

    /**
     * Tokens for the recipient the notification pipeline resolved. Device rows are
     * keyed by user_account, so the mapping back to (recipient_type, recipient_id)
     * goes through user_account(subject_type, subject_id).
     */
    public List<DeviceToken> tokensForRecipient(String recipientType, UUID recipientId) {
        return jdbc.query(
            "SELECT d.id, d.token, d.platform FROM notification_device d " +
            "JOIN user_account u ON u.id = d.user_account_id " +
            "WHERE u.subject_type = ? AND u.subject_id = ? AND u.is_active",
            (rs, n) -> new DeviceToken(rs.getObject("id", UUID.class), rs.getString("token"), rs.getString("platform")),
            recipientType, recipientId
        );
    }
}
