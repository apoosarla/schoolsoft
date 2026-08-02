package com.schoolsoft.curriculum.api;

import java.util.UUID;

public record LearningOutcomeDto(
    UUID id,
    UUID curriculumNodeId,
    String code,
    String statement,
    String bloomLevel,
    int sortOrder
) {}
