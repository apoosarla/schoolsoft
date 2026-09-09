package com.schoolsoft.comms.internal;

import com.schoolsoft.comms.api.AnnouncementDto;
import com.schoolsoft.notification.api.DeliveryStats;
import com.schoolsoft.notification.api.Notice;
import com.schoolsoft.notification.api.NotificationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Publishing an announcement and sending it are one use case.
 *
 * <p>They were two before: {@code publish} stamped a timestamp and nothing
 * else happened, so a school closure notice looked published to the office and
 * reached nobody (COMM-06). The office cannot see the difference from the
 * announcement row, which is why {@link #deliveryFor} exists — a broadcast
 * that reports only "published" is unfalsifiable.</p>
 *
 * <p>The fan-out is deduplicated on the announcement's own id, so a second
 * publish — a retry, a double-click, a redelivery of the same request — stamps
 * nothing and sends nothing.</p>
 */
@Service
public class AnnouncementPublisher {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementPublisher.class);

    private final CommsRepository repo;
    private final NotificationService notifications;

    public AnnouncementPublisher(CommsRepository repo, NotificationService notifications) {
        this.repo = repo;
        this.notifications = notifications;
    }

    public AnnouncementDto publish(UUID id) {
        boolean firstPublish = repo.markPublished(id);
        AnnouncementDto announcement = repo.find(id);
        if (!firstPublish) {
            log.info("Announcement {} was already published at {} — no second fan-out",
                id, announcement.publishedAt());
            return announcement;
        }

        List<UUID> audience = repo.audienceStudentIds(announcement);
        int reached = notifications.notifyGuardiansOfStudents(audience,
            Notice.of(announcement.schoolId(), templateFor(announcement), Map.of(
                    "title", announcement.title(),
                    "body", announcement.body(),
                    "priority", announcement.priority()))
                .via(announcement.channels())
                .about("announcement", id)
                .oncePer("announcement:" + id));

        log.info("Announcement {} ({}, scope={}) published to {} student(s), {} guardian(s) reached",
            id, announcement.priority(), announcement.scopeType(), audience.size(), reached);
        return announcement;
    }

    public DeliveryStats deliveryFor(UUID announcementId) {
        repo.find(announcementId);   // 404 rather than empty stats for an id that is not ours
        return notifications.deliveryFor("announcement", announcementId);
    }

    /**
     * An emergency reads differently from a newsletter, and on WhatsApp it has
     * to be a different approved template rather than the same one with a
     * louder title (§10).
     */
    private static String templateFor(AnnouncementDto announcement) {
        return "emergency".equals(announcement.priority()) ? "emergency_broadcast" : "announcement_published";
    }
}
