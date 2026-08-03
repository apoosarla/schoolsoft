package com.schoolsoft.lms.api;

import java.time.Instant;
import java.util.UUID;

public record ContentItemDto(
    UUID id,
    UUID schoolId,
    UUID subjectId,
    UUID curriculumNodeId,
    String title,
    String visibility,
    UUID createdByStaffId,
    Instant createdAt
) {}
