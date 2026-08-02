package com.schoolsoft.curriculum.api;

import java.util.UUID;

public record CurriculumTemplateDto(
    UUID id,
    String boardCode,
    String strategyCode,
    String name,
    String version,
    String gradeBand
) {}
