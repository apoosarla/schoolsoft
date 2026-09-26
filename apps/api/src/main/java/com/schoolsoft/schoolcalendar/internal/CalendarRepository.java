package com.schoolsoft.schoolcalendar.internal;

import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.schoolcalendar.api.CalendarEntryDto;
import com.schoolsoft.schoolcalendar.api.WorkingDayPatternDto;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CalendarRepository {

    private final JdbcTemplate jdbc;

    public CalendarRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Internal read shapes: what WorkingDayService classifies against.
    public record CalendarEntry(UUID id, LocalDate onDate, String kind, String title,
                                UUID gradeId, UUID campusId) {}

    public record Pattern(UUID id, UUID campusId, LocalDate effectiveFrom, LocalDate effectiveTo,
                          String weekdayMask, String saturdayRule, String saturdayWeeks) {}

    private static final RowMapper<CalendarEntry> ENTRY = (rs, i) -> new CalendarEntry(
        UUID.fromString(rs.getString("id")),
        rs.getDate("on_date").toLocalDate(),
        rs.getString("kind"),
        rs.getString("title"),
        rs.getString("grade_id") == null ? null : UUID.fromString(rs.getString("grade_id")),
        rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id"))
    );

    private static final RowMapper<Pattern> PATTERN = (rs, i) -> new Pattern(
        UUID.fromString(rs.getString("id")),
        rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id")),
        rs.getDate("effective_from").toLocalDate(),
        rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(),
        rs.getString("weekday_mask"),
        rs.getString("saturday_rule"),
        rs.getString("saturday_weeks")
    );

    private static final RowMapper<CalendarEntryDto> ENTRY_DTO = (rs, i) -> new CalendarEntryDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("academic_year_id") == null ? null : UUID.fromString(rs.getString("academic_year_id")),
        rs.getDate("on_date").toLocalDate(),
        rs.getString("kind"),
        rs.getString("title"),
        rs.getString("description"),
        rs.getString("grade_id") == null ? null : UUID.fromString(rs.getString("grade_id")),
        rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id")),
        rs.getString("source"),
        rs.getString("declared_by_staff_id") == null ? null : UUID.fromString(rs.getString("declared_by_staff_id")),
        rs.getTimestamp("declared_at").toInstant()
    );

    private static final String ENTRY_COLS =
        "id, school_id, academic_year_id, on_date, kind, title, description, grade_id, campus_id, " +
        "source, declared_by_staff_id, declared_at";

    private static final RowMapper<WorkingDayPatternDto> PATTERN_DTO = (rs, i) -> new WorkingDayPatternDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id")),
        rs.getDate("effective_from").toLocalDate(),
        rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate(),
        rs.getString("weekday_mask"),
        rs.getString("saturday_rule"),
        rs.getString("saturday_weeks"),
        rs.getString("notes")
    );

    private static final String PATTERN_COLS =
        "id, school_id, campus_id, effective_from, effective_to, weekday_mask, saturday_rule, "
        + "saturday_weeks, notes";

    // ------------------------------------------------------------ working days

    /**
     * Patterns that could apply to this school (and campus, when asked),
     * ordered so the first match in
     * {@code WorkingDayService#patternInForce} is the right one: a
     * campus-specific pattern beats the school-wide one, and a newer
     * effective_from beats an older.
     *
     * <p>{@code created_at} breaks the tie, and it is not decoration: two
     * patterns starting on the same day sorted only by {@code effective_from}
     * come back in whatever order Postgres felt like, so a school could be
     * told it teaches on Saturday and told it does not, by two identical
     * requests. The later write wins, which is what a correction means.</p>
     */
    public List<Pattern> patternsFor(UUID schoolId, UUID campusId) {
        return jdbc.query(
            "SELECT " + PATTERN_COLS + " FROM working_day_pattern " +
            "WHERE school_id = ? AND (campus_id IS NULL OR campus_id = ?) " +
            "ORDER BY (campus_id IS NOT NULL) DESC, effective_from DESC, created_at DESC",
            PATTERN, schoolId, campusId
        );
    }

    /**
     * Calendar entries in range that apply to the given scope. A NULL grade or
     * campus on the row means "everyone", so a query for one grade sees both
     * the school-wide entries and its own.
     */
    public List<CalendarEntry> entriesInRange(UUID schoolId, LocalDate from, LocalDate to,
                                              UUID gradeId, UUID campusId) {
        return jdbc.query(
            "SELECT " + ENTRY_COLS + " FROM school_calendar " +
            "WHERE school_id = ? AND on_date BETWEEN ? AND ? " +
            "  AND (grade_id IS NULL OR grade_id = ?) " +
            "  AND (campus_id IS NULL OR campus_id = ?) " +
            "ORDER BY on_date",
            ENTRY, schoolId, Date.valueOf(from), Date.valueOf(to), gradeId, campusId
        );
    }

    // ------------------------------------------------------------------ writes

    public WorkingDayPatternDto upsertPattern(UUID schoolId, UUID campusId, LocalDate effectiveFrom,
                                              LocalDate effectiveTo, String weekdayMask,
                                              String saturdayRule, String saturdayWeeks, String notes) {
        if (!weekdayMask.matches("[01]{7}")) {
            throw new IllegalArgumentException(
                "weekdayMask must be 7 characters of 0/1, Monday first — got: " + weekdayMask);
        }
        if (!List.of("all", "none", "odd", "even", "nth").contains(saturdayRule)) {
            throw new IllegalArgumentException("saturdayRule must be all | none | odd | even | nth");
        }
        // 'nth' is the school whose Saturdays are a set rather than a rule —
        // the 2nd and 4th, or the 1st alone. Told here rather than left to the
        // CHECK constraint, so the caller reads which Saturdays it failed to
        // name instead of a constraint's name out of a 409.
        String weeks = saturdayWeeks == null || saturdayWeeks.isBlank() ? null : saturdayWeeks.trim();
        if ("nth".equals(saturdayRule)) {
            if (weeks == null || !weeks.matches("[01]{5}")) {
                throw new IllegalArgumentException(
                    "saturdayWeeks must be 5 characters of 0/1 — the 1st to 5th Saturday of the month, "
                    + "'1' = taught — when saturdayRule is 'nth'. Got: " + saturdayWeeks);
            }
            if (!weeks.contains("1")) {
                throw new IllegalArgumentException(
                    "saturdayWeeks names no Saturday at all; a school that teaches on none of them "
                    + "has saturdayRule 'none'.");
            }
        } else if (weeks != null) {
            throw new IllegalArgumentException(
                "saturdayWeeks only means something under saturdayRule 'nth'; the '" + saturdayRule
                + "' rule already says which Saturdays count.");
        }
        // The week this school keeps changed on a date, so the week it kept
        // before that ended the day before. Without this every pattern stays
        // open forever and the screen shows three of them all "in force",
        // leaving the reader to work out which one an attendance percentage
        // was actually computed against. Only patterns that started earlier
        // are closed: one starting on the same day or later is a correction to
        // a future week, and the ordering above decides between those.
        jdbc.update(
            "UPDATE working_day_pattern SET effective_to = ?::date - 1 " +
            "WHERE school_id = ? AND effective_to IS NULL AND effective_from < ?::date " +
            "  AND campus_id IS NOT DISTINCT FROM ?",
            Date.valueOf(effectiveFrom), schoolId, Date.valueOf(effectiveFrom), campusId);

        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO working_day_pattern (id, school_id, campus_id, effective_from, effective_to, " +
            "  weekday_mask, saturday_rule, saturday_weeks, notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, campusId, Date.valueOf(effectiveFrom),
            effectiveTo == null ? null : Date.valueOf(effectiveTo), weekdayMask, saturdayRule, weeks, notes
        );
        return jdbc.queryForObject(
            "SELECT " + PATTERN_COLS + " FROM working_day_pattern WHERE id = ?", PATTERN_DTO, id);
    }

    public List<WorkingDayPatternDto> listPatterns(UUID schoolId) {
        return jdbc.query(
            "SELECT " + PATTERN_COLS + " FROM working_day_pattern WHERE school_id = ? " +
            "ORDER BY effective_from DESC", PATTERN_DTO, schoolId);
    }

    public List<CalendarEntryDto> listEntries(UUID schoolId, LocalDate from, LocalDate to) {
        return jdbc.query(
            "SELECT " + ENTRY_COLS + " FROM school_calendar WHERE school_id = ? AND on_date BETWEEN ? AND ? " +
            "ORDER BY on_date", ENTRY_DTO, schoolId, Date.valueOf(from), Date.valueOf(to));
    }

    public CalendarEntryDto findEntry(UUID id) {
        var rows = jdbc.query("SELECT " + ENTRY_COLS + " FROM school_calendar WHERE id = ?", ENTRY_DTO, id);
        if (rows.isEmpty()) throw new NotFoundException("Calendar entry not found: " + id);
        return rows.get(0);
    }

    /**
     * Idempotent on the row's natural key (school, date, kind, grade, campus),
     * so re-importing a gazetted holiday list updates titles instead of piling
     * up duplicates.
     */
    public CalendarEntryDto upsertEntry(UUID schoolId, UUID academicYearId, LocalDate onDate, String kind,
                                        String title, String description, UUID gradeId, UUID campusId,
                                        String source, UUID declaredByStaffId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO school_calendar (id, school_id, academic_year_id, on_date, kind, title, description, " +
            "  grade_id, campus_id, source, declared_by_staff_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (school_id, on_date, kind, grade_id, campus_id) DO UPDATE SET " +
            "  title = EXCLUDED.title, description = EXCLUDED.description, " +
            "  academic_year_id = EXCLUDED.academic_year_id, source = EXCLUDED.source, " +
            "  declared_by_staff_id = EXCLUDED.declared_by_staff_id, declared_at = now()",
            id, schoolId, academicYearId, Date.valueOf(onDate), kind, title, description,
            gradeId, campusId, source, declaredByStaffId
        );
        var rows = jdbc.query(
            "SELECT " + ENTRY_COLS + " FROM school_calendar WHERE school_id = ? AND on_date = ? AND kind = ? " +
            "  AND grade_id IS NOT DISTINCT FROM ? AND campus_id IS NOT DISTINCT FROM ?",
            ENTRY_DTO, schoolId, Date.valueOf(onDate), kind, gradeId, campusId
        );
        return rows.get(0);
    }

    public void deleteEntry(UUID id) {
        int deleted = jdbc.update("DELETE FROM school_calendar WHERE id = ?", id);
        if (deleted == 0) throw new NotFoundException("Calendar entry not found: " + id);
    }

    // --------------------------------------------------------------- closures

    /**
     * Voids the day's attendance for the scope a closure covers, retaining the
     * rows. Returns the students whose marks were voided so the caller can tell
     * their guardians.
     */
    public List<UUID> voidAttendanceFor(UUID schoolId, LocalDate onDate, UUID gradeId, UUID campusId,
                                        String reason, UUID voidedByStaffId) {
        StringBuilder sql = new StringBuilder(
            "UPDATE attendance_record ar SET voided_at = now(), void_reason = ?, voided_by_staff_id = ? " +
            "FROM section s WHERE s.id = ar.section_id AND ar.school_id = ? AND ar.on_date = ? " +
            "  AND ar.voided_at IS NULL ");
        List<Object> args = new ArrayList<>(List.of(reason, voidedByStaffId, schoolId, Date.valueOf(onDate)));
        if (gradeId != null) {
            sql.append("AND s.grade_id = ? ");
            args.add(gradeId);
        }
        if (campusId != null) {
            sql.append("AND s.campus_id = ? ");
            args.add(campusId);
        }
        sql.append("RETURNING ar.student_id");

        return jdbc.query(sql.toString(),
            (rs, i) -> UUID.fromString(rs.getString("student_id")), args.toArray());
    }

    /** Guardians to notify about a closure, for the students whose day it affects. */
    public List<UUID> guardianIdsForStudents(List<UUID> studentIds) {
        if (studentIds.isEmpty()) return List.of();
        String placeholders = String.join(",", studentIds.stream().map(s -> "?").toList());
        return jdbc.query(
            "SELECT DISTINCT guardian_id FROM guardian_student " +
            "WHERE student_id IN (" + placeholders + ") AND is_communications_recipient",
            (rs, i) -> UUID.fromString(rs.getString("guardian_id")), studentIds.toArray()
        );
    }
}
