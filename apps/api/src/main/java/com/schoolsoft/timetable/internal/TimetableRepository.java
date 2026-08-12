package com.schoolsoft.timetable.internal;

import com.schoolsoft.enrolment.api.StudentSubjectDto;
import com.schoolsoft.enrolment.api.SubjectSetResolver;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.schoolcalendar.api.WorkingDayService;
import com.schoolsoft.timetable.api.SectionDayDto;
import com.schoolsoft.timetable.api.TimetableSlotDto;
import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TimetableRepository {

    private final JdbcTemplate jdbc;
    private final WorkingDayService workingDays;
    private final SubjectSetResolver subjectSets;

    public TimetableRepository(JdbcTemplate jdbc, WorkingDayService workingDays, SubjectSetResolver subjectSets) {
        this.jdbc = jdbc;
        this.workingDays = workingDays;
        this.subjectSets = subjectSets;
    }

    private static final RowMapper<TimetableSlotDto> MAPPER = (rs, i) -> new TimetableSlotDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("section_id")),
        UUID.fromString(rs.getString("subject_id")),
        rs.getString("subject_name"),
        UUID.fromString(rs.getString("teacher_staff_id")),
        rs.getInt("day_of_week"),
        rs.getInt("period_no"),
        rs.getTime("starts_at").toLocalTime(),
        rs.getTime("ends_at").toLocalTime(),
        rs.getString("room"),
        rs.getDate("effective_from").toLocalDate(),
        rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate()
    );

    private static final String SELECT =
        "SELECT t.id, t.section_id, t.subject_id, sub.name AS subject_name, t.teacher_staff_id, t.day_of_week, " +
        "       t.period_no, t.starts_at, t.ends_at, t.room, t.effective_from, t.effective_to " +
        "FROM timetable_slot t JOIN subject sub ON sub.id = t.subject_id ";

    public List<TimetableSlotDto> forSection(UUID sectionId) {
        return jdbc.query(SELECT + "WHERE t.section_id = ? ORDER BY t.day_of_week, t.period_no", MAPPER, sectionId);
    }

    public List<TimetableSlotDto> forTeacher(UUID teacherStaffId) {
        return jdbc.query(SELECT + "WHERE t.teacher_staff_id = ? ORDER BY t.day_of_week, t.period_no", MAPPER, teacherStaffId);
    }

    /**
     * A student's week: their section's slots, minus the periods for subjects
     * they do not take. In a section with option blocks the section timetable
     * and any one student's timetable are different documents (ACAD-09).
     */
    public List<TimetableSlotDto> forStudent(UUID studentId, LocalDate onDate) {
        LocalDate date = onDate == null ? LocalDate.now() : onDate;
        var enrolments = jdbc.query(
            "SELECT section_id FROM enrolment WHERE student_id = ? " +
            "  AND starts_on <= ? AND COALESCE(ends_on, 'infinity'::date) >= ? " +
            "ORDER BY (status = 'active') DESC, starts_on DESC LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString("section_id")),
            studentId, Date.valueOf(date), Date.valueOf(date));
        if (enrolments.isEmpty()) return List.of();

        var studied = subjectSets.forStudent(studentId, date).stream()
            .map(StudentSubjectDto::subjectId).collect(java.util.stream.Collectors.toSet());
        return forSection(enrolments.get(0)).stream()
            .filter(slot -> studied.contains(slot.subjectId()))
            .toList();
    }

    /**
     * The section's day for a specific date, resolved against the school
     * calendar (CAL-03). On a holiday, a vacation day or a declared closure the
     * period list is empty and the reason says why — the caller renders "school
     * closed", not a blank grid.
     */
    public SectionDayDto forSectionOnDate(UUID sectionId, LocalDate date) {
        var scope = jdbc.query(
            "SELECT school_id, grade_id, campus_id FROM section WHERE id = ?",
            (rs, i) -> new UUID[]{
                UUID.fromString(rs.getString("school_id")),
                UUID.fromString(rs.getString("grade_id")),
                rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id"))
            },
            sectionId);
        if (scope.isEmpty()) throw new NotFoundException("Section not found: " + sectionId);

        var status = workingDays.statusOf(scope.get(0)[0], date, scope.get(0)[1], scope.get(0)[2]);
        if (!status.working()) {
            return new SectionDayDto(date, false, status.reason(), status.calendarKind(), List.of());
        }

        // A date is known here, so the slot's effective window is applied — the
        // week view has no date to apply it with (TT-05 remains open).
        List<TimetableSlotDto> slots = jdbc.query(
            SELECT + "WHERE t.section_id = ? AND t.day_of_week = ? " +
            "  AND t.effective_from <= ? AND COALESCE(t.effective_to, 'infinity'::date) >= ? " +
            "ORDER BY t.period_no",
            MAPPER, sectionId, date.getDayOfWeek().getValue(), Date.valueOf(date), Date.valueOf(date));
        return new SectionDayDto(date, true, status.reason(), status.calendarKind(), slots);
    }

    /**
     * Creates a slot, refusing the two double-bookings that matter: the same
     * teacher in two places at once, and two sections in the same room at the
     * same time (TT-03 — the room half was missing entirely).
     *
     * With {@code periodId} the times come from the bell schedule and the
     * caller's own times are ignored; without it the slot carries its own,
     * which is what every slot did before Phase 2.
     */
    public TimetableSlotDto createSlot(
        UUID sectionId, UUID subjectId, UUID teacherStaffId, int dayOfWeek, int periodNo,
        LocalTime startsAt, LocalTime endsAt, String room, LocalDate effectiveFrom, LocalDate effectiveTo,
        UUID periodId
    ) {
        int slotPeriodNo = periodNo;
        LocalTime from = startsAt;
        LocalTime to = endsAt;
        if (periodId != null) {
            var period = jdbc.query(
                "SELECT period_no, starts_at, ends_at, is_break FROM bell_period WHERE id = ?",
                (rs, i) -> new Object[]{ rs.getInt("period_no"), rs.getTime("starts_at").toLocalTime(),
                                         rs.getTime("ends_at").toLocalTime(), rs.getBoolean("is_break") },
                periodId);
            if (period.isEmpty()) throw new NotFoundException("Bell period not found: " + periodId);
            if ((Boolean) period.get(0)[3]) {
                throw new IllegalArgumentException("That period is a break; nothing can be timetabled into it");
            }
            slotPeriodNo = (Integer) period.get(0)[0];
            from = (LocalTime) period.get(0)[1];
            to = (LocalTime) period.get(0)[2];
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("A slot needs either a periodId or explicit start and end times");
        }
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("Slot ends at or before it starts");
        }

        requireNoTeacherClash(teacherStaffId, dayOfWeek, from, to, effectiveFrom, effectiveTo, null);
        requireNoRoomClash(room, dayOfWeek, from, to, effectiveFrom, effectiveTo, null);

        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timetable_slot (id, section_id, subject_id, teacher_staff_id, day_of_week, period_no, " +
            "  starts_at, ends_at, room, effective_from, effective_to, period_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, sectionId, subjectId, teacherStaffId, dayOfWeek, slotPeriodNo,
            Time.valueOf(from), Time.valueOf(to), room, Date.valueOf(effectiveFrom),
            effectiveTo == null ? null : Date.valueOf(effectiveTo), periodId
        );
        return jdbc.queryForObject(SELECT + "WHERE t.id = ?", MAPPER, id);
    }

    private void requireNoTeacherClash(UUID teacherStaffId, int dayOfWeek, LocalTime from, LocalTime to,
                                       LocalDate effectiveFrom, LocalDate effectiveTo, UUID ignoreSlotId) {
        Integer clashes = jdbc.queryForObject(
            "SELECT count(*) FROM timetable_slot WHERE teacher_staff_id = ? AND day_of_week = ? " +
            "  AND starts_at < ? AND ends_at > ? " +
            "  AND effective_from <= COALESCE(?, 'infinity'::date) AND COALESCE(effective_to, 'infinity'::date) >= ? " +
            "  AND (?::uuid IS NULL OR id <> ?::uuid)",
            Integer.class,
            teacherStaffId, dayOfWeek, Time.valueOf(to), Time.valueOf(from),
            effectiveTo == null ? null : Date.valueOf(effectiveTo), Date.valueOf(effectiveFrom),
            ignoreSlotId, ignoreSlotId);
        if (clashes != null && clashes > 0) {
            throw new IllegalArgumentException("Teacher already has an overlapping timetable slot on day " + dayOfWeek);
        }
    }

    private void requireNoRoomClash(String room, int dayOfWeek, LocalTime from, LocalTime to,
                                    LocalDate effectiveFrom, LocalDate effectiveTo, UUID ignoreSlotId) {
        if (room == null || room.isBlank()) return;         // unassigned room cannot clash
        var occupant = jdbc.query(
            "SELECT (g.code || '-' || sec.code) AS section_label FROM timetable_slot t " +
            "JOIN section sec ON sec.id = t.section_id JOIN grade g ON g.id = sec.grade_id " +
            "WHERE t.room = ? AND t.day_of_week = ? AND t.starts_at < ? AND t.ends_at > ? " +
            "  AND t.effective_from <= COALESCE(?, 'infinity'::date) " +
            "  AND COALESCE(t.effective_to, 'infinity'::date) >= ? " +
            "  AND (?::uuid IS NULL OR t.id <> ?::uuid) LIMIT 1",
            (rs, i) -> rs.getString("section_label"),
            room, dayOfWeek, Time.valueOf(to), Time.valueOf(from),
            effectiveTo == null ? null : Date.valueOf(effectiveTo), Date.valueOf(effectiveFrom),
            ignoreSlotId, ignoreSlotId);
        if (!occupant.isEmpty()) {
            throw new IllegalArgumentException(
                "Room " + room + " is already booked on day " + dayOfWeek + " by " + occupant.get(0));
        }
    }

    /**
     * Publish-time checks that are warnings rather than refusals (TT-04): a
     * teacher over their weekly period ceiling, and periods left unstaffed. A
     * school publishes an imperfect timetable on purpose in week one; it should
     * do so knowing what is wrong with it.
     */
    public List<String> publishWarnings(UUID sectionId) {
        List<String> warnings = new java.util.ArrayList<>();

        warnings.addAll(jdbc.query(
            "SELECT (st.first_name || ' ' || COALESCE(st.last_name, '')) AS name, " +
            "       st.max_weekly_periods AS ceiling, count(*) AS load " +
            "FROM timetable_slot t JOIN staff st ON st.id = t.teacher_staff_id " +
            "WHERE st.max_weekly_periods IS NOT NULL " +
            "  AND st.id IN (SELECT teacher_staff_id FROM timetable_slot WHERE section_id = ?) " +
            "GROUP BY st.id, st.first_name, st.last_name, st.max_weekly_periods " +
            "HAVING count(*) > st.max_weekly_periods",
            (rs, i) -> rs.getString("name").trim() + " is timetabled for " + rs.getInt("load")
                + " periods a week, over their maximum of " + rs.getInt("ceiling"),
            sectionId));

        Integer unroomed = jdbc.queryForObject(
            "SELECT count(*) FROM timetable_slot WHERE section_id = ? AND (room IS NULL OR room = '')",
            Integer.class, sectionId);
        if (unroomed != null && unroomed > 0) {
            warnings.add(unroomed + " slot(s) have no room assigned");
        }
        return warnings;
    }

    public void deleteSlot(UUID id) {
        jdbc.update("DELETE FROM timetable_slot WHERE id = ?", id);
    }
}
