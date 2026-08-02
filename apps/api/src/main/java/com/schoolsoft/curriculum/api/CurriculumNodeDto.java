package com.schoolsoft.curriculum.api;

import java.util.UUID;

public record CurriculumNodeDto(
    UUID id,
    UUID curriculumId,
    UUID parentId,
    String nodeType,
    String code,
    String name,
    int sortOrder,
    String path,
    int depth
) {}
