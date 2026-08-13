package com.schoolsoft.assessment.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One supersession of a mark. The mark row holds the current value; this holds
 * what it was, what it became, why, and who allowed it — so a re-evaluation
 * never destroys the number the teacher originally entered (ASMT-08).
 */
public record MarkRevisionDto(
    UUID id,
    UUID markId,
    int revisionNo,
    String kind,
    Double oldRawMarks,
    String oldStatus,
    String oldGradeLetter,
    Double newRawMarks,
    String newStatus,
    String newGradeLetter,
    String reason,
    UUID changedByUserId,
    Instant changedAt
) {}
