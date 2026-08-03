package com.schoolsoft.lms.api;

import java.time.Instant;
import java.util.UUID;

public record AssignmentDto(
    UUID id,
    UUID schoolId,
    UUID sectionId,
    UUID subjectId,
    String title,
    String instructions,
    String submissionType,
    Instant dueAt,
    Double maxMarks,
    String status,
    UUID createdByStaffId
) {}
