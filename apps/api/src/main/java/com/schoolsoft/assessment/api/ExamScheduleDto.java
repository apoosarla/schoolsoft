package com.schoolsoft.assessment.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An exam window — the board's "Half Yearly" or "Mock" week (ASMT-09). */
public record ExamScheduleDto(
    UUID id,
    UUID schoolId,
    UUID academicYearId,
    UUID termId,
    String code,
    String name,
    LocalDate startsOn,
    LocalDate endsOn,
    String status,
    Instant publishedAt,
    int sessionCount
) {}
