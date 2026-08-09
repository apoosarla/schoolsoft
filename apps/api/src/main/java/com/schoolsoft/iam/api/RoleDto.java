package com.schoolsoft.iam.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoleDto(
    UUID id,
    String code,
    String name,
    String description,
    List<String> screenKeys,
    boolean isSystem,
    Instant createdAt
) {}
