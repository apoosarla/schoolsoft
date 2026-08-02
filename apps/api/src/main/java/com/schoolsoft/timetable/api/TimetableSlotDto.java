package com.schoolsoft.timetable.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record TimetableSlotDto(
    UUID id,
    UUID sectionId,
    UUID subjectId,
    String subjectName,
    UUID teacherStaffId,
    int dayOfWeek,
    int periodNo,
    LocalTime startsAt,
    LocalTime endsAt,
    String room,
    LocalDate effectiveFrom,
    LocalDate effectiveTo
) {}
