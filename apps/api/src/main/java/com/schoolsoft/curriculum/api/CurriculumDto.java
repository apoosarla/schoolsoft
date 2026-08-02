package com.schoolsoft.curriculum.api;

import java.time.Instant;
import java.util.UUID;

public record CurriculumDto(
    UUID id,
    UUID schoolId,
    String boardCode,
    String strategyCode,
    String name,
    String version,
    UUID gradeId,
    UUID subjectId,
    UUID sourceTemplateId,
    boolean isPublished,
    Instant createdAt
) {}
