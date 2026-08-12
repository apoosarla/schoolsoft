package com.schoolsoft.tenancy.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code isCurrent} is the fast "which year are we in" lookup;
 * {@code status} (planning | active | closed) is what governs whether the
 * year's attendance, marks and fees may still be written to.
 */
public record AcademicYearDto(
    UUID id,
    String code,
    LocalDate startsOn,
    LocalDate endsOn,
    boolean isCurrent,
    String status,
    Instant closedAt,
    Instant reopenedAt,
    String reopenReason
) {}
