package com.schoolsoft.timetable.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A teacher's own day (TT-08).
 *
 * {@code slots} is what they teach — with the periods somebody else has been
 * asked to cover removed, because a teacher on leave should not see a class
 * they are not taking. {@code covering} is the other side of the same rule: the
 * periods handed to them for this date only.
 */
public record TeacherDayDto(
    UUID teacherStaffId,
    LocalDate date,
    boolean working,
    String reason,
    List<TimetableSlotDto> slots,
    List<TimetableCoverDto> covering,
    List<TimetableCoverDto> coveredForThem
) {}
