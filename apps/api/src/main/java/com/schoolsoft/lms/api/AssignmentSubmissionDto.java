package com.schoolsoft.lms.api;

import java.time.Instant;
import java.util.UUID;

public record AssignmentSubmissionDto(
    UUID id,
    UUID assignmentId,
    UUID studentId,
    String body,
    Instant submittedAt,
    Double marks,
    String feedback,
    Instant gradedAt
) {}
