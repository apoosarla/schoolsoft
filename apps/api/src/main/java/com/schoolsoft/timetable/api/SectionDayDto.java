package com.schoolsoft.timetable.api;

import java.time.LocalDate;
import java.util.List;

/**
 * A section's schedule for one calendar date (CAL-03).
 *
 * A closed day carries {@code working = false} and the reason the school is
 * shut, with no periods — which is what lets a teacher or parent app say
 * "school closed: Independence Day" rather than render an empty week and leave
 * the reader guessing whether the timetable simply had not been published.
 *
 * {@code covers} names the periods whose teacher is away and who is taking them
 * instead, keyed by slot id — so the section is told who is walking through the
 * door, not just that somebody is (TT-08).
 */
public record SectionDayDto(
    LocalDate date,
    boolean working,
    String reason,
    String calendarKind,
    List<TimetableSlotDto> slots,
    List<TimetableCoverDto> covers,
    boolean examDay,
    List<com.schoolsoft.assessment.api.ExamSessionDto> examSessions
) {
    /**
     * An ordinary day: no exam sitting, so the class timetable stands.
     *
     * During an exam week it does not — the papers replace the periods rather
     * than sitting alongside them, and a section shown both would send a class
     * to a lesson that is not happening (TT-09).
     */
    public static SectionDayDto teaching(LocalDate date, boolean working, String reason, String calendarKind,
                                         List<TimetableSlotDto> slots, List<TimetableCoverDto> covers) {
        return new SectionDayDto(date, working, reason, calendarKind, slots, covers, false, List.of());
    }
}
