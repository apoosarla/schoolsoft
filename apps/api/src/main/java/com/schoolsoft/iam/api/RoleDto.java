package com.schoolsoft.iam.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code version} is what makes an edit safe: the client sends back the version
 * it read, and a save against a stale one is refused rather than allowed to
 * overwrite whoever saved in between. See {@code V028__optimistic_locking.sql}.
 */
public record RoleDto(
    UUID id,
    String code,
    String name,
    String description,
    List<String> screenKeys,
    boolean isSystem,
    long version,
    Instant createdAt
) {}
