package com.schoolsoft.lms.api;

import java.time.LocalDate;
import java.util.UUID;

public record LessonPlanDto(
    UUID id,
    UUID schoolId,
    UUID sectionId,
    UUID subjectId,
    UUID curriculumNodeId,
    String title,
    LocalDate plannedFor,
    Integer durationMinutes,
    String status,
    UUID createdByStaffId
) {}
