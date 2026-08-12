package com.schoolsoft.timetable.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** One period of one day handed to somebody else (TT-08). */
public record TimetableCoverDto(
    UUID id,
    UUID schoolId,
    UUID slotId,
    UUID sectionId,
    String sectionLabel,
    UUID subjectId,
    String subjectName,
    LocalDate onDate,
    int periodNo,
    LocalTime startsAt,
    LocalTime endsAt,
    String room,
    UUID absentStaffId,
    String absentStaffName,
    UUID substituteStaffId,
    String substituteStaffName,
    String reason,
    UUID leaveApplicationId,
    boolean cancelled
) {}
