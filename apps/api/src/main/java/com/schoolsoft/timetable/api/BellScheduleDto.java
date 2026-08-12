package com.schoolsoft.timetable.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * The school day for a grade band: which periods exist, when they run, and
 * which of them are breaks. A slot references a period rather than repeating
 * its times, so moving the bell moves every affected lesson at once (GAP-12).
 */
public record BellScheduleDto(
    UUID id,
    UUID schoolId,
    UUID campusId,
    String code,
    String name,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    List<UUID> gradeIds,
    List<Period> periods
) {
    public record Period(
        UUID id, int periodNo, String label, LocalTime startsAt, LocalTime endsAt, boolean isBreak
    ) {}
}
