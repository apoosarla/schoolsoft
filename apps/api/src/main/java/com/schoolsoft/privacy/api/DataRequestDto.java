package com.schoolsoft.privacy.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param overdue still open past {@code dueOn} — the row the office has to act on first
 */
public record DataRequestDto(
    UUID id, UUID schoolId, UUID studentId, String kind, String status, String note,
    LocalDate requestedOn, LocalDate dueOn, boolean overdue,
    Instant decidedAt, String decisionReason
) {}
