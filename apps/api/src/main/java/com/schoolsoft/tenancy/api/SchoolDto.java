package com.schoolsoft.tenancy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code lifecycle} is whether the school has been opened — {@code draft}
 * until somebody declares its setup finished, then {@code live}. It is not
 * {@code isActive}, which is the row's own soft delete.
 */
public record SchoolDto(
    UUID id,
    String slug,
    String name,
    String boardCode,
    String gstin,
    String stateCode,
    boolean isActive,
    String lifecycle,
    Instant wentLiveAt
) {}
