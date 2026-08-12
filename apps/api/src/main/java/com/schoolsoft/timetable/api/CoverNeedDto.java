package com.schoolsoft.timetable.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * A period left without its teacher on a date, with the people who could take
 * it (STF-03).
 *
 * The candidate list is part of the answer rather than a second call: the
 * person filling the gap is doing it between two of their own lessons, and the
 * only useful screen is the one that shows who is free right then.
 */
public record CoverNeedDto(
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
    UUID leaveApplicationId,
    TimetableCoverDto cover,
    List<CandidateDto> candidates
) {
    /** A teacher who is free in this period and not themselves on leave. */
    public record CandidateDto(UUID staffId, String name, int periodsThatDay) {}
}
