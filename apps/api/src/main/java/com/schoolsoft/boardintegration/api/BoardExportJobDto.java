package com.schoolsoft.boardintegration.api;

import java.time.Instant;
import java.util.UUID;

public record BoardExportJobDto(
    UUID id,
    UUID schoolId,
    String boardCode,
    String exportType,
    UUID academicYearId,
    UUID sectionId,
    UUID studentId,
    String status,
    String errorMessage,
    Instant createdAt,
    Instant completedAt
) {}
