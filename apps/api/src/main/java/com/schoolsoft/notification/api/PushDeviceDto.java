package com.schoolsoft.notification.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A registered push target. The token itself is never echoed back. */
public record PushDeviceDto(
    UUID id,
    UUID userAccountId,
    String platform,
    OffsetDateTime createdAt,
    OffsetDateTime lastSeenAt
) {}
