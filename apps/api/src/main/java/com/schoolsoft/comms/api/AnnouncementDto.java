package com.schoolsoft.comms.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnnouncementDto(
    UUID id,
    UUID schoolId,
    String scopeType,
    List<UUID> scopeIds,
    String title,
    String body,
    List<String> channels,
    Instant publishedAt,
    Instant expiresAt,
    UUID createdByUserId,
    Instant createdAt
) {}
