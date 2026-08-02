package com.schoolsoft.tenancy.api;

import java.util.UUID;

public record SectionDto(
    UUID id,
    UUID schoolId,
    UUID gradeId,
    String gradeName,
    UUID academicYearId,
    String code,
    String name,
    UUID curriculumId,
    String strategyCode,
    Integer capacity
) {}
