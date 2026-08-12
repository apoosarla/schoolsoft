package com.schoolsoft.schoolcalendar.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A school's (or campus's) normal week, effective-dated.
 * {@code weekdayMask} is Monday-first, '1' = taught.
 */
public record WorkingDayPatternDto(
    UUID id,
    UUID schoolId,
    UUID campusId,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String weekdayMask,
    String saturdayRule,
    String notes
) {}
