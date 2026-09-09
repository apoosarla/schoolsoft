package com.schoolsoft.timetable.internal;

import com.schoolsoft.enrolment.api.StudentSubjectDto;
import com.schoolsoft.enrolment.api.SubjectSetResolver;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.schoolcalendar.api.WorkingDayService;
import com.schoolsoft.timetable.api.SectionDayDto;
import com.schoolsoft.timetable.api.TeacherDayDto;
import com.schoolsoft.timetable.api.TimetableCoverDto;
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
    private final CoverRepository covers;
    private final com.schoolsoft.assessment.api.ExamSchedules exams;

    public TimetableRepository(JdbcTemplate jdbc, WorkingDayService workingDays,
                              SubjectSetResolver subjectSets, CoverRepository covers,
                              com.schoolsoft.assessment.api.ExamSchedules exams) {
        this.jdbc = jdbc;
        this.workingDays = workingDays;
        this.subjectSets = subjectSets;
        this.covers = covers;
        this.exams = exams;
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

    /**
     * The window predicate, in one place. A slot is part of the timetable on a
     * date; "the timetable" with no date is not a thing the school has, because
     * a mid-year revision supersedes rather than replaces (TT-05). Takes the
     * date twice.
     */
    private static final String IN_FORCE =
        "  AND t.effective_from <= ? AND COALESCE(t.effective_to, 'infinity'::date) >= ? ";

    /** The section's week as it stands on {@code onDate}, superseded slots excluded. */
    public List<TimetableSlotDto> forSection(UUID sectionId, LocalDate onDate) {
        LocalDate date = onDate == null ? LocalDate.now() : onDate;
        return jdbc.query(
            SELECT + "WHERE t.section_id = ? " + IN_FORCE + "ORDER BY t.day_of_week, t.period_no",
            MAPPER, sectionId, Date.valueOf(date), Date.valueOf(date));
    }

    /** The teacher's week as it stands on {@code onDate}. */
    public List<TimetableSlotDto> forTeacher(UUID teacherStaffId, LocalDate onDate) {
        LocalDate date = onDate == null ? LocalDate.now() : onDate;
        return jdbc.query(
            SELECT + "WHERE t.teacher_staff_id = ? " + IN_FORCE + "ORDER BY t.day_of_week, t.period_no",
            MAPPER, teacherStaffId, Date.valueOf(date), Date.valueOf(date));
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
        return forSection(enrolments.get(0), date).stream()
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
            return SectionDayDto.teaching(date, false, status.reason(), status.calendarKind(), List.of(), List.of());
        }

        // An exam sitting replaces the day's lessons rather than joining them:
        // during exam week the class does not go to its periods, and a view
        // showing both sends thirty children to a lesson nobody is teaching
        // (TT-09).
        var papers = exams.publishedSessionsForGradeOn(scope.get(0)[1], date);
        if (!papers.isEmpty()) {
            return new SectionDayDto(date, true,
                "Exam day — the regular timetable is suspended", "exam_day",
                List.of(), List.of(), true, papers);
        }

        List<TimetableSlotDto> slots = jdbc.query(
            SELECT + "WHERE t.section_id = ? AND t.day_of_week = ? " + IN_FORCE +
            "ORDER BY t.period_no",
            MAPPER, sectionId, date.getDayOfWeek().getValue(), Date.valueOf(date), Date.valueOf(date));
        return SectionDayDto.teaching(date, true, status.reason(), status.calendarKind(), slots,
            covers.forSection(sectionId, date));
    }

    /**
     * A teacher's day, cover included (TT-08). Their own periods minus the ones
     * someone else is taking, plus the ones they have been given — which is the
     * list the teacher app renders as "today".
     */
    public TeacherDayDto forTeacherOnDate(UUID teacherStaffId, LocalDate date) {
        var schools = jdbc.query("SELECT school_id FROM staff WHERE id = ?",
            (rs, i) -> UUID.fromString(rs.getString("school_id")), teacherStaffId);
        if (schools.isEmpty()) throw new NotFoundException("Staff member not found: " + teacherStaffId);

        var status = workingDays.statusOf(schools.get(0), date, null, null);
        if (!status.working()) {
            return new TeacherDayDto(teacherStaffId, date, false, status.reason(),
                List.of(), List.of(), List.of());
        }

        List<TimetableCoverDto> covering = covers.forSubstitute(teacherStaffId, date);
        List<TimetableCoverDto> handedOver = covers.forAbsentee(teacherStaffId, date);
        var handedOverSlots = handedOver.stream().map(TimetableCoverDto::slotId)
            .collect(java.util.stream.Collectors.toSet());

        List<TimetableSlotDto> own = jdbc.query(
            SELECT + "WHERE t.teacher_staff_id = ? AND t.day_of_week = ? " + IN_FORCE +
            "ORDER BY t.period_no",
            MAPPER, teacherStaffId, date.getDayOfWeek().getValue(),
            Date.valueOf(date), Date.valueOf(date)).stream()
            .filter(slot -> !handedOverSlots.contains(slot.id()))
            .toList();

        return new TeacherDayDto(teacherStaffId, date, true, status.reason(), own, covering, handedOver);
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
    public List<String> publishWarnings(UUID sectionId, LocalDate onDate) {
        LocalDate date = onDate == null ? LocalDate.now() : onDate;
        Date d = Date.valueOf(date);
        List<String> warnings = new java.util.ArrayList<>();

        // A teacher's load is the load they carry on the date being published,
        // not every period they have ever been timetabled for: a slot the
        // revision superseded is somebody else's problem now.
        warnings.addAll(jdbc.query(
            "SELECT (st.first_name || ' ' || COALESCE(st.last_name, '')) AS name, " +
            "       st.max_weekly_periods AS ceiling, count(*) AS load " +
            "FROM timetable_slot t JOIN staff st ON st.id = t.teacher_staff_id " +
            "WHERE st.max_weekly_periods IS NOT NULL " + IN_FORCE +
            "  AND st.id IN (SELECT teacher_staff_id FROM timetable_slot l WHERE l.section_id = ? " +
            "                  AND l.effective_from <= ? " +
            "                  AND COALESCE(l.effective_to, 'infinity'::date) >= ?) " +
            "GROUP BY st.id, st.first_name, st.last_name, st.max_weekly_periods " +
            "HAVING count(*) > st.max_weekly_periods",
            (rs, i) -> rs.getString("name").trim() + " is timetabled for " + rs.getInt("load")
                + " periods a week, over their maximum of " + rs.getInt("ceiling"),
            d, d, sectionId, d, d));

        Integer unroomed = jdbc.queryForObject(
            "SELECT count(*) FROM timetable_slot t WHERE t.section_id = ? AND (t.room IS NULL OR t.room = '') "
                + IN_FORCE,
            Integer.class, sectionId, d, d);
        if (unroomed != null && unroomed > 0) {
            warnings.add(unroomed + " slot(s) have no room assigned");
        }
        return warnings;
    }

    /**
     * Retires a slot from the end of {@code lastDay} — the supersession half of
     * a mid-year revision (TT-05). The slot keeps existing, so the attendance,
     * lesson plans and cover already hung off it still resolve; it simply stops
     * being part of the timetable from the day after. The replacement is an
     * ordinary {@code createSlot} with {@code effectiveFrom = lastDay + 1},
     * which the clash checks then let through because the windows do not meet.
     *
     * <p>One conditional UPDATE naming the window it moves out of: a slot may
     * only have its window shortened, never lengthened, because lengthening one
     * puts a period back into a week that has already been taught. Re-running
     * the same retirement is not an error.
     */
    public TimetableSlotDto retireSlot(UUID id, LocalDate lastDay) {
        var current = jdbc.query(
            "SELECT effective_from, effective_to FROM timetable_slot WHERE id = ?",
            (rs, i) -> new LocalDate[]{
                rs.getDate("effective_from").toLocalDate(),
                rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate()
            },
            id);
        if (current.isEmpty()) throw new NotFoundException("Timetable slot not found: " + id);
        if (lastDay.isBefore(current.get(0)[0])) {
            throw new IllegalArgumentException(
                "A slot cannot be retired before it starts (it runs from " + current.get(0)[0]
                    + "); delete it instead");
        }

        int rows = jdbc.update(
            "UPDATE timetable_slot SET effective_to = ? " +
            "WHERE id = ? AND COALESCE(effective_to, 'infinity'::date) >= ?",
            Date.valueOf(lastDay), id, Date.valueOf(lastDay));
        if (rows == 0) {
            throw new ConflictException(
                "That slot already stopped running on " + current.get(0)[1]
                    + "; a window can be shortened but not lengthened");
        }
        return jdbc.queryForObject(SELECT + "WHERE t.id = ?", MAPPER, id);
    }

    public void deleteSlot(UUID id) {
        jdbc.update("DELETE FROM timetable_slot WHERE id = ?", id);
    }
}
