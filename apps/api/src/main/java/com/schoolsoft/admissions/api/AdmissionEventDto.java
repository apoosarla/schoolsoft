package com.schoolsoft.admissions.api;

import java.time.Instant;
import java.util.UUID;

public record AdmissionEventDto(
    UUID id,
    UUID applicationId,
    String eventType,
    String fromState,
    String toState,
    Instant occurredAt
) {}
