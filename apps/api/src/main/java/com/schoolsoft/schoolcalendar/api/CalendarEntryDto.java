package com.schoolsoft.schoolcalendar.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One exceptional date: holiday, vacation day, working Saturday, closure, or exam day. */
public record CalendarEntryDto(
    UUID id,
    UUID schoolId,
    UUID academicYearId,
    LocalDate onDate,
    String kind,
    String title,
    String description,
    UUID gradeId,
    UUID campusId,
    String source,
    UUID declaredByStaffId,
    Instant declaredAt
) {}
