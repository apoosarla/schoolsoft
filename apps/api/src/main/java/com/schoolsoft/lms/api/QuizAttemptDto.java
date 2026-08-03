package com.schoolsoft.lms.api;

import java.time.Instant;
import java.util.UUID;

public record QuizAttemptDto(
    UUID id, UUID quizId, UUID studentId, Instant startedAt, Instant submittedAt, Double score
) {}
