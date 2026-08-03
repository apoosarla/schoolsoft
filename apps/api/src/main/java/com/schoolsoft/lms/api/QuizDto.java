package com.schoolsoft.lms.api;

import java.util.UUID;

public record QuizDto(
    UUID id, UUID schoolId, UUID subjectId, String title, Integer durationMinutes, boolean randomise, boolean lockdown
) {}
