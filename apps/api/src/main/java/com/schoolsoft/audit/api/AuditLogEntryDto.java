package com.schoolsoft.audit.api;

import java.time.Instant;
import java.util.UUID;

public record AuditLogEntryDto(
    long id, UUID schoolId, UUID actorUserId, String action, String targetType, UUID targetId,
    String reason, Instant occurredAt
) {}
