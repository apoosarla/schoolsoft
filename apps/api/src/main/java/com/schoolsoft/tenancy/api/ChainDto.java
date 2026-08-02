package com.schoolsoft.tenancy.api;

import java.time.Instant;
import java.util.UUID;

public record ChainDto(
    UUID id,
    String slug,
    String name,
    String schemaName,
    String planCode,
    String region,
    String status,
    int schemaVersion,
    Instant createdAt
) {}
