package com.schoolsoft.assessment.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A family's request to have a paper looked at again, and what the school did
 * about it. Kept whatever the outcome: "we re-read it and the mark stands" is
 * an answer a parent is owed, and one the school has to be able to evidence.
 */
public record MarkReevaluationDto(
    UUID id,
    UUID markId,
    UUID studentId,
    String reason,
    UUID requestedByUserId,
    Instant requestedAt,
    String status,
    UUID decidedByUserId,
    Instant decidedAt,
    String decisionNote,
    UUID revisionId
) {}
