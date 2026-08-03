package com.schoolsoft.lms.api;

import java.util.UUID;

public record QuizQuestionDto(
    UUID id, UUID quizId, UUID curriculumNodeId, String kind, String prompt, double marks, int sortOrder
) {}
