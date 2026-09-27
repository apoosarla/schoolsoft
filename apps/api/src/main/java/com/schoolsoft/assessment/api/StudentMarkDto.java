package com.schoolsoft.assessment.api;

import java.time.LocalDate;
import java.util.UUID;

/** One mark of one child, with the assessment and component it belongs to — the family's grades list. */
public record StudentMarkDto(
    UUID assessmentId,
    String assessmentName,
    String assessmentType,
    String subjectName,
    LocalDate scheduledOn,
    String assessmentStatus,
    UUID componentId,
    String componentName,
    double maxMarks,
    UUID markId,
    Double rawMarks,
    String status
) {}
