package com.schoolsoft.assessment.api;

import java.time.LocalDate;
import java.util.UUID;

public record AssessmentDto(
    UUID id,
    UUID schoolId,
    UUID sectionId,
    UUID subjectId,
    UUID termId,
    String strategyCode,
    String name,
    String assessmentType,
    Double maxMarks,
    Double weightPct,
    LocalDate scheduledOn,
    String status
) {}
