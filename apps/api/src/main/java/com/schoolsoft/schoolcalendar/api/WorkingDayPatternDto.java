package com.schoolsoft.schoolcalendar.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A school's (or campus's) normal week, effective-dated.
 * {@code weekdayMask} is Monday-first, '1' = taught.
 *
 * <p>{@code saturdayRule} is one of {@code all | none | odd | even | nth}.
 * Under {@code nth} — the school that teaches on, say, the 2nd and 4th
 * Saturday and no others — {@code saturdayWeeks} carries the set as a
 * five-character mask over the 1st..5th Saturday of the calendar month,
 * '1' = taught. It is null under every other rule.</p>
 */
public record WorkingDayPatternDto(
    UUID id,
    UUID schoolId,
    UUID campusId,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String weekdayMask,
    String saturdayRule,
    String saturdayWeeks,
    String notes
) {}
