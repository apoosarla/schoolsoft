package com.schoolsoft.schoolcalendar.api;

import com.schoolsoft.schoolcalendar.internal.CalendarRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The single authority on whether a given date is a school day, and on how many
 * school days sit in a range. Every attendance percentage and every fee
 * due-date computation goes through here; no module computes its own
 * denominator, which is what stops two screens disagreeing about the same
 * child's attendance.
 *
 * Resolution order for a date:
 *   1. a {@code closure} or {@code holiday}/{@code vacation} calendar entry in
 *      scope wins — the school was shut;
 *   2. a {@code working_saturday} entry in scope makes an otherwise-off
 *      Saturday count;
 *   3. otherwise the working-day pattern in force on that date decides.
 *
 * Scope narrows from the school down: an entry with no grade and no campus
 * applies to everyone, one with a grade applies to that cohort, one with a
 * campus applies to that campus. Asking about a specific grade or campus
 * therefore sees both the school-wide entries and its own.
 */
@Service
public class WorkingDayService {

    private final CalendarRepository repo;

    public WorkingDayService(CalendarRepository repo) { this.repo = repo; }

    /** A day's status, with the reason attached — the reason is what a UI shows. */
    public record DayStatus(LocalDate date, boolean working, String reason, String calendarKind) {}

    public boolean isWorkingDay(UUID schoolId, LocalDate date, UUID gradeId, UUID campusId) {
        return statusOf(schoolId, date, gradeId, campusId).working();
    }

    public DayStatus statusOf(UUID schoolId, LocalDate date, UUID gradeId, UUID campusId) {
        List<CalendarRepository.CalendarEntry> entries =
            repo.entriesInRange(schoolId, date, date, gradeId, campusId);
        return classify(schoolId, date, entries, repo.patternsFor(schoolId, campusId));
    }

    /** Every working day in {@code [from, to]}, inclusive. */
    public List<LocalDate> workingDays(UUID schoolId, LocalDate from, LocalDate to, UUID gradeId, UUID campusId) {
        List<LocalDate> days = new ArrayList<>();
        for (DayStatus status : calendar(schoolId, from, to, gradeId, campusId)) {
            if (status.working()) days.add(status.date());
        }
        return days;
    }

    public int countWorkingDays(UUID schoolId, LocalDate from, LocalDate to, UUID gradeId, UUID campusId) {
        return workingDays(schoolId, from, to, gradeId, campusId).size();
    }

    /**
     * Day-by-day status across a range. One query for the calendar and one for
     * the patterns, then classification in memory — a month view must not be
     * thirty round trips.
     */
    public List<DayStatus> calendar(UUID schoolId, LocalDate from, LocalDate to, UUID gradeId, UUID campusId) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Range ends before it starts: " + from + " .. " + to);
        }
        var entries = repo.entriesInRange(schoolId, from, to, gradeId, campusId);
        var patterns = repo.patternsFor(schoolId, campusId);

        List<DayStatus> statuses = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            statuses.add(classify(schoolId, date, entries, patterns));
        }
        return statuses;
    }

    /**
     * The next working day on or after {@code date} — what a fee due date
     * shifts to when it lands on a holiday.
     */
    public LocalDate nextWorkingDayOnOrAfter(UUID schoolId, LocalDate date, UUID gradeId, UUID campusId) {
        LocalDate cursor = date;
        for (int i = 0; i < 366; i++) {
            if (isWorkingDay(schoolId, cursor, gradeId, campusId)) return cursor;
            cursor = cursor.plusDays(1);
        }
        throw new IllegalStateException(
            "No working day within a year of " + date + " for school " + schoolId
            + " — check the working-day pattern");
    }

    // --------------------------------------------------------------- internals

    private DayStatus classify(UUID schoolId, LocalDate date,
                               List<CalendarRepository.CalendarEntry> entries,
                               List<CalendarRepository.Pattern> patterns) {
        CalendarRepository.CalendarEntry closure = null;
        CalendarRepository.CalendarEntry workingSaturday = null;
        for (var entry : entries) {
            if (!entry.onDate().equals(date)) continue;
            switch (entry.kind()) {
                case "closure", "holiday", "vacation" -> {
                    if (closure == null) closure = entry;
                }
                case "working_saturday" -> {
                    if (workingSaturday == null) workingSaturday = entry;
                }
                default -> { /* exam_day does not change whether school is open */ }
            }
        }

        // A declared closure beats everything, including a working Saturday.
        if (closure != null) {
            return new DayStatus(date, false, closure.title(), closure.kind());
        }
        if (workingSaturday != null) {
            return new DayStatus(date, true, workingSaturday.title(), workingSaturday.kind());
        }

        CalendarRepository.Pattern pattern = patternInForce(patterns, date);
        if (pattern == null) {
            // No pattern configured: fall back to a Monday-Friday week rather than
            // claiming every day is a school day.
            boolean weekday = date.getDayOfWeek().getValue() <= DayOfWeek.FRIDAY.getValue();
            return new DayStatus(date, weekday, weekday ? "Default weekday" : "Weekend", null);
        }
        return applyPattern(pattern, date);
    }

    private CalendarRepository.Pattern patternInForce(List<CalendarRepository.Pattern> patterns, LocalDate date) {
        // Repository returns campus-specific before school-wide, newest first.
        for (var pattern : patterns) {
            boolean started = !date.isBefore(pattern.effectiveFrom());
            boolean notEnded = pattern.effectiveTo() == null || !date.isAfter(pattern.effectiveTo());
            if (started && notEnded) return pattern;
        }
        return null;
    }

    private DayStatus applyPattern(CalendarRepository.Pattern pattern, LocalDate date) {
        int index = date.getDayOfWeek().getValue() - 1;              // Mon=0 .. Sun=6
        boolean workingByMask = pattern.weekdayMask().charAt(index) == '1';
        if (!workingByMask) {
            return new DayStatus(date, false, "Non-working weekday", null);
        }
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) {
            int nth = (date.getDayOfMonth() - 1) / 7 + 1;            // 1st..5th Saturday
            boolean working = switch (pattern.saturdayRule()) {
                case "none" -> false;
                case "odd"  -> nth % 2 == 1;
                case "even" -> nth % 2 == 0;
                default     -> true;
            };
            return new DayStatus(date, working,
                working ? "Working Saturday (" + pattern.saturdayRule() + " rule)"
                        : "Off Saturday (" + pattern.saturdayRule() + " rule)", null);
        }
        return new DayStatus(date, true, "School day", null);
    }
}
