package com.schoolsoft.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Attendance over a range, against a working-day denominator.
 *
 * {@code workingDays} is what the school calendar says the student could have
 * attended within their enrolment window; {@code consideredDays} removes
 * approved leave and excused days, and is what {@code percentage} divides by.
 * Both are reported so a parent can see why the number is what it is.
 */
public record AttendanceSummaryDto(
    UUID studentId,
    LocalDate from,
    LocalDate to,
    LocalDate enrolledFrom,
    LocalDate enrolledTo,
    int workingDays,
    int consideredDays,
    int present,
    int absent,
    int late,
    int halfDay,
    int onLeave,
    int excused,
    Double percentage
) {}
