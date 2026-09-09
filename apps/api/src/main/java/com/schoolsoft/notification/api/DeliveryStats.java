package com.schoolsoft.notification.api;

import java.time.Instant;
import java.util.Map;

/**
 * What actually went out for one thing — an announcement, an invoice, a trip.
 *
 * <p>A broadcast that reports only "published" is unfalsifiable: the office
 * needs to see that the fan-out ran, on which channels, and how long it took
 * from the publish to the last send.</p>
 *
 * @param recipients distinct people reached, as opposed to rows written
 * @param elapsedMs  first queue to last send — the number an SLA is read against
 */
public record DeliveryStats(
    String relatedType,
    java.util.UUID relatedId,
    int recipients,
    int dispatches,
    int sent,
    int failed,
    int pending,
    Map<String, Integer> byChannel,
    Map<String, Integer> byStatus,
    Instant firstQueuedAt,
    Instant lastSentAt,
    Long elapsedMs
) {}
