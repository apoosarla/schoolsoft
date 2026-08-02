package com.schoolsoft.assessment.api;

import java.util.UUID;

public record MarkDto(
    UUID id,
    UUID assessmentComponentId,
    UUID studentId,
    Double rawMarks,
    String gradeLetter,
    String remarks,
    boolean isAbsent
) {}
