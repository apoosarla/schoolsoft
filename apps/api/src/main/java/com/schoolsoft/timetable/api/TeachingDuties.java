package com.schoolsoft.timetable.api;

import com.schoolsoft.timetable.internal.CoverRepository;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The timetable module's answer to "is this period this person's to take?" —
 * asked by attendance before it lets somebody mark a register (TT-08).
 *
 * It lives here because the answer is a timetable fact: who is scheduled, and
 * who has been handed the period for one day. Attendance owning its own copy of
 * that rule is how the two drift.
 */
@Service
public class TeachingDuties {

    private final JdbcTemplate jdbc;
    private final CoverRepository covers;

    public TeachingDuties(JdbcTemplate jdbc, CoverRepository covers) {
        this.jdbc = jdbc;
        this.covers = covers;
    }

    /**
     * Whether the staff member is timetabled to teach this section on this
     * date. A null {@code periodNo} asks about the day as a whole, which is
     * the question day-level attendance asks.
     */
    public boolean teachesOn(UUID staffId, UUID sectionId, LocalDate onDate, Integer periodNo) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM timetable_slot t " +
            "WHERE t.teacher_staff_id = ? AND t.section_id = ? AND t.day_of_week = ? " +
            "  AND t.effective_from <= ? AND COALESCE(t.effective_to, 'infinity'::date) >= ? " +
            "  AND (?::int IS NULL OR t.period_no = ?::int)",
            Integer.class, staffId, sectionId, onDate.getDayOfWeek().getValue(),
            Date.valueOf(onDate), Date.valueOf(onDate), periodNo, periodNo);
        return n != null && n > 0;
    }

    /** Whether the staff member holds a cover assignment for that period. */
    public boolean isCovering(UUID staffId, UUID sectionId, LocalDate onDate, Integer periodNo) {
        return covers.isCovering(staffId, sectionId, onDate, periodNo);
    }

    /** Whether they are the section's primary (class) teacher. */
    public boolean isPrimaryTeacherOf(UUID staffId, UUID sectionId) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM section_subject_teacher " +
            "WHERE section_id = ? AND teacher_staff_id = ? AND is_primary",
            Integer.class, sectionId, staffId);
        return n != null && n > 0;
    }
}
