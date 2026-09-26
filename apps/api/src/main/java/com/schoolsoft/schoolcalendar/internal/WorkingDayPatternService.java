package com.schoolsoft.schoolcalendar.internal;

import com.schoolsoft.schoolcalendar.api.WorkingDayPatternDto;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoring a school's working week (CAL-09, CAL-10).
 *
 * A new pattern is two writes: the pattern it supersedes is closed the day
 * before, then the new one is inserted. They stand or fall together. Closed
 * without the insert, the school has no week at all from the change onwards
 * and every date after it falls back to the Monday-Friday default — a
 * denominator nobody chose, under every attendance percentage.
 */
@Service
public class WorkingDayPatternService {

    private final CalendarRepository repo;

    public WorkingDayPatternService(CalendarRepository repo) { this.repo = repo; }

    @Transactional
    public WorkingDayPatternDto create(UUID schoolId, UUID campusId, LocalDate effectiveFrom,
                                       LocalDate effectiveTo, String weekdayMask,
                                       String saturdayRule, String saturdayWeeks, String notes) {
        return repo.upsertPattern(schoolId, campusId, effectiveFrom, effectiveTo,
            weekdayMask, saturdayRule, saturdayWeeks, notes);
    }
}
