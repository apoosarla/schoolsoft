package com.schoolsoft.assessment.api;

import java.util.UUID;

public record AssessmentComponentDto(
    UUID id,
    UUID assessmentId,
    String code,
    String name,
    double maxMarks,
    Double weightPct,
    int sortOrder
) {}
