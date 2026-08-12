package com.schoolsoft.timetable.internal;

import com.schoolsoft.notification.api.NotificationService;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.schoolcalendar.api.WorkingDayService;
import com.schoolsoft.timetable.api.CoverNeedDto;
import com.schoolsoft.timetable.api.TimetableCoverDto;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Cover assignment — who takes a period whose teacher is away (GAP-07).
 *
 * A cover is per slot per date, not per absence: a teacher out for a week has
 * thirty periods, and a school fills them from thirty different gaps in thirty
 * different people's days.
 *
 * Assigning cover does three things at once, and all three matter: the
 * substitute's own day view gains the period, the section's day view names the
 * substitute instead of the absent teacher, and the substitute becomes
 * authorised to mark that period's attendance — which is the only reason the
 * children in front of them get registered at all.
 */
@Repository
public class CoverRepository {

    private final JdbcTemplate jdbc;
    private final WorkingDayService workingDays;
    private final NotificationService notifications;

    public CoverRepository(JdbcTemplate jdbc, WorkingDayService workingDays,
                           NotificationService notifications) {
        this.jdbc = jdbc;
        this.workingDays = workingDays;
        this.notifications = notifications;
    }

    private static final String COVER_SELECT =
        "SELECT c.id, c.school_id, c.slot_id, t.section_id, (g.code || '-' || sec.code) AS section_label, " +
        "       t.subject_id, sub.name AS subject_name, c.on_date, t.period_no, t.starts_at, t.ends_at, t.room, " +
        "       c.absent_staff_id, (absent.first_name || ' ' || COALESCE(absent.last_name, '')) AS absent_name, " +
        "       c.substitute_staff_id, (subst.first_name || ' ' || COALESCE(subst.last_name, '')) AS substitute_name, " +
        "       c.reason, c.leave_application_id, c.cancelled_at " +
        "FROM timetable_cover c " +
        "JOIN timetable_slot t ON t.id = c.slot_id " +
        "JOIN section sec ON sec.id = t.section_id " +
        "JOIN grade g ON g.id = sec.grade_id " +
        "JOIN subject sub ON sub.id = t.subject_id " +
        "JOIN staff absent ON absent.id = c.absent_staff_id " +
        "JOIN staff subst ON subst.id = c.substitute_staff_id ";

    private static final RowMapper<TimetableCoverDto> COVER_MAPPER = (rs, i) -> new TimetableCoverDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("slot_id")),
        UUID.fromString(rs.getString("section_id")),
        rs.getString("section_label"),
        UUID.fromString(rs.getString("subject_id")),
        rs.getString("subject_name"),
        rs.getDate("on_date").toLocalDate(),
        rs.getInt("period_no"),
        rs.getTime("starts_at").toLocalTime(),
        rs.getTime("ends_at").toLocalTime(),
        rs.getString("room"),
        UUID.fromString(rs.getString("absent_staff_id")),
        rs.getString("absent_name").trim(),
        UUID.fromString(rs.getString("substitute_staff_id")),
        rs.getString("substitute_name").trim(),
        rs.getString("reason"),
        rs.getString("leave_application_id") == null ? null
            : UUID.fromString(rs.getString("leave_application_id")),
        rs.getTimestamp("cancelled_at") != null
    );

    // ------------------------------------------------------------ assignment

    /** A slot, resolved far enough to validate a cover against it. */
    private record Slot(UUID sectionId, UUID schoolId, UUID gradeId, UUID campusId, UUID teacherStaffId,
                        int dayOfWeek, int periodNo, java.sql.Time startsAt, java.sql.Time endsAt,
                        LocalDate effectiveFrom, LocalDate effectiveTo) {}

    private Slot slot(UUID slotId) {
        var rows = jdbc.query(
            "SELECT t.section_id, sec.school_id, sec.grade_id, sec.campus_id, t.teacher_staff_id, " +
            "       t.day_of_week, t.period_no, t.starts_at, t.ends_at, t.effective_from, t.effective_to " +
            "FROM timetable_slot t JOIN section sec ON sec.id = t.section_id WHERE t.id = ?",
            (rs, i) -> new Slot(
                UUID.fromString(rs.getString("section_id")),
                UUID.fromString(rs.getString("school_id")),
                UUID.fromString(rs.getString("grade_id")),
                rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id")),
                UUID.fromString(rs.getString("teacher_staff_id")),
                rs.getInt("day_of_week"), rs.getInt("period_no"),
                rs.getTime("starts_at"), rs.getTime("ends_at"),
                rs.getDate("effective_from").toLocalDate(),
                rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate()),
            slotId);
        if (rows.isEmpty()) throw new NotFoundException("Timetable slot not found: " + slotId);
        return rows.get(0);
    }

    public TimetableCoverDto assign(UUID slotId, LocalDate onDate, UUID substituteStaffId, String reason) {
        Slot slot = slot(slotId);

        if (slot.dayOfWeek() != onDate.getDayOfWeek().getValue()) {
            throw new IllegalArgumentException(
                "That period does not run on " + onDate + " (" + onDate.getDayOfWeek() + ")");
        }
        if (slot.effectiveFrom().isAfter(onDate)
            || (slot.effectiveTo() != null && slot.effectiveTo().isBefore(onDate))) {
            throw new IllegalArgumentException("The slot is not in force on " + onDate);
        }
        var day = workingDays.statusOf(slot.schoolId(), onDate, slot.gradeId(), slot.campusId());
        if (!day.working()) {
            throw new IllegalArgumentException("No cover is needed on " + onDate + ": " + day.reason());
        }
        if (substituteStaffId.equals(slot.teacherStaffId())) {
            throw new IllegalArgumentException("The absent teacher cannot cover their own period");
        }
        requireSameSchool(substituteStaffId, slot.schoolId());
        requireSubstituteFree(substituteStaffId, slot, onDate);

        Integer existing = jdbc.queryForObject(
            "SELECT count(*) FROM timetable_cover WHERE slot_id = ? AND on_date = ? AND cancelled_at IS NULL",
            Integer.class, slotId, Date.valueOf(onDate));
        if (existing != null && existing > 0) {
            throw new ConflictException("That period already has cover on " + onDate);
        }

        UUID leaveId = approvedLeaveOf(slot.teacherStaffId(), onDate);
        var snap = TenantContext.get();
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timetable_cover (id, school_id, slot_id, on_date, absent_staff_id, " +
            "  substitute_staff_id, reason, leave_application_id, created_by_user_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, slot.schoolId(), slotId, Date.valueOf(onDate), slot.teacherStaffId(),
            substituteStaffId, reason, leaveId, snap == null ? null : snap.userAccountId());

        TimetableCoverDto cover = find(id);
        announce(cover);
        return cover;
    }

    private void requireSameSchool(UUID staffId, UUID schoolId) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM staff WHERE id = ? AND school_id = ? AND is_active",
            Integer.class, staffId, schoolId);
        if (n == null || n == 0) {
            throw new IllegalArgumentException("Substitute " + staffId + " is not active staff of this school");
        }
    }

    /**
     * A substitute who is teaching elsewhere, already covering elsewhere, or on
     * leave themselves is not a substitute — they are the same absence one
     * period later.
     */
    private void requireSubstituteFree(UUID substituteStaffId, Slot slot, LocalDate onDate) {
        Integer ownSlots = jdbc.queryForObject(
            "SELECT count(*) FROM timetable_slot WHERE teacher_staff_id = ? AND day_of_week = ? " +
            "  AND starts_at < ? AND ends_at > ? " +
            "  AND effective_from <= ? AND COALESCE(effective_to, 'infinity'::date) >= ?",
            Integer.class, substituteStaffId, slot.dayOfWeek(), slot.endsAt(), slot.startsAt(),
            Date.valueOf(onDate), Date.valueOf(onDate));
        if (ownSlots != null && ownSlots > 0) {
            throw new IllegalArgumentException("That teacher has their own class in this period");
        }

        Integer otherCovers = jdbc.queryForObject(
            "SELECT count(*) FROM timetable_cover c JOIN timetable_slot t ON t.id = c.slot_id " +
            "WHERE c.substitute_staff_id = ? AND c.on_date = ? AND c.cancelled_at IS NULL " +
            "  AND t.starts_at < ? AND t.ends_at > ?",
            Integer.class, substituteStaffId, Date.valueOf(onDate), slot.endsAt(), slot.startsAt());
        if (otherCovers != null && otherCovers > 0) {
            throw new IllegalArgumentException("That teacher is already covering another class in this period");
        }

        if (approvedLeaveOf(substituteStaffId, onDate) != null) {
            throw new IllegalArgumentException("That teacher is on approved leave on " + onDate);
        }
    }

    private UUID approvedLeaveOf(UUID staffId, LocalDate onDate) {
        var rows = jdbc.query(
            "SELECT id FROM leave_application WHERE subject_type = 'staff' AND subject_id = ? " +
            "  AND status = 'approved' AND from_date <= ? AND to_date >= ? LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString("id")), staffId, Date.valueOf(onDate), Date.valueOf(onDate));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The substitute and the section's primary teacher both need to know. */
    private void announce(TimetableCoverDto cover) {
        notifications.notify(cover.schoolId(), "staff", cover.substituteStaffId(),
            "timetable.cover_assigned",
            Map.of("section", cover.sectionLabel(), "subject", cover.subjectName(),
                   "date", cover.onDate().toString(), "period", cover.periodNo(),
                   "room", cover.room() == null ? "" : cover.room()));

        for (UUID primary : jdbc.query(
            "SELECT DISTINCT teacher_staff_id FROM section_subject_teacher WHERE section_id = ? AND is_primary",
            (rs, i) -> UUID.fromString(rs.getString("teacher_staff_id")), cover.sectionId())) {
            if (primary.equals(cover.substituteStaffId())) continue;
            notifications.notify(cover.schoolId(), "staff", primary, "timetable.cover_assigned",
                Map.of("section", cover.sectionLabel(), "subject", cover.subjectName(),
                       "date", cover.onDate().toString(), "period", cover.periodNo(),
                       "substitute", cover.substituteStaffName()));
        }
    }

    public TimetableCoverDto cancel(UUID id) {
        var snap = TenantContext.get();
        int updated = jdbc.update(
            "UPDATE timetable_cover SET cancelled_at = now(), cancelled_by_user_id = ? " +
            "WHERE id = ? AND cancelled_at IS NULL",
            snap == null ? null : snap.userAccountId(), id);
        if (updated == 0) throw new NotFoundException("Open cover assignment not found: " + id);
        return find(id);
    }

    // ---------------------------------------------------------------- reads

    public TimetableCoverDto find(UUID id) {
        var rows = jdbc.query(COVER_SELECT + "WHERE c.id = ?", COVER_MAPPER, id);
        if (rows.isEmpty()) throw new NotFoundException("Cover assignment not found: " + id);
        return rows.get(0);
    }

    public List<TimetableCoverDto> forSubstitute(UUID staffId, LocalDate onDate) {
        return jdbc.query(
            COVER_SELECT + "WHERE c.substitute_staff_id = ? AND c.on_date = ? AND c.cancelled_at IS NULL " +
            "ORDER BY t.period_no", COVER_MAPPER, staffId, Date.valueOf(onDate));
    }

    /** The periods somebody else is taking for this staff member on a date. */
    public List<TimetableCoverDto> forAbsentee(UUID staffId, LocalDate onDate) {
        return jdbc.query(
            COVER_SELECT + "WHERE c.absent_staff_id = ? AND c.on_date = ? AND c.cancelled_at IS NULL " +
            "ORDER BY t.period_no", COVER_MAPPER, staffId, Date.valueOf(onDate));
    }

    public List<TimetableCoverDto> forSection(UUID sectionId, LocalDate onDate) {
        return jdbc.query(
            COVER_SELECT + "WHERE t.section_id = ? AND c.on_date = ? AND c.cancelled_at IS NULL " +
            "ORDER BY t.period_no", COVER_MAPPER, sectionId, Date.valueOf(onDate));
    }

    public List<TimetableCoverDto> forSchool(UUID schoolId, LocalDate onDate) {
        return jdbc.query(
            COVER_SELECT + "WHERE c.school_id = ? AND c.on_date = ? AND c.cancelled_at IS NULL " +
            "ORDER BY t.period_no", COVER_MAPPER, schoolId, Date.valueOf(onDate));
    }

    /**
     * Whether this staff member holds cover in a section on a date — the
     * question attendance asks before letting them mark a register that is not
     * theirs. A null {@code periodNo} asks about the day.
     */
    public boolean isCovering(UUID staffId, UUID sectionId, LocalDate onDate, Integer periodNo) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM timetable_cover c JOIN timetable_slot t ON t.id = c.slot_id " +
            "WHERE c.substitute_staff_id = ? AND t.section_id = ? AND c.on_date = ? " +
            "  AND c.cancelled_at IS NULL AND (?::int IS NULL OR t.period_no = ?::int)",
            Integer.class, staffId, sectionId, Date.valueOf(onDate), periodNo, periodNo);
        return n != null && n > 0;
    }

    /**
     * The day's uncovered (and covered) periods for a school, driven by
     * approved staff leave (STF-03). This is the screen a head of school opens
     * at 07:45.
     */
    public List<CoverNeedDto> needs(UUID schoolId, LocalDate onDate) {
        record Need(UUID slotId, UUID sectionId, String sectionLabel, UUID subjectId, String subjectName,
                    int periodNo, java.sql.Time startsAt, java.sql.Time endsAt, String room,
                    UUID absentStaffId, String absentName, UUID leaveId) {}

        List<Need> needs = jdbc.query(
            "SELECT t.id AS slot_id, t.section_id, (g.code || '-' || sec.code) AS section_label, " +
            "       t.subject_id, sub.name AS subject_name, t.period_no, t.starts_at, t.ends_at, t.room, " +
            "       t.teacher_staff_id, (st.first_name || ' ' || COALESCE(st.last_name, '')) AS absent_name, " +
            "       la.id AS leave_id " +
            "FROM timetable_slot t " +
            "JOIN section sec ON sec.id = t.section_id " +
            "JOIN grade g ON g.id = sec.grade_id " +
            "JOIN subject sub ON sub.id = t.subject_id " +
            "JOIN staff st ON st.id = t.teacher_staff_id " +
            "JOIN leave_application la ON la.subject_type = 'staff' AND la.subject_id = t.teacher_staff_id " +
            "  AND la.status = 'approved' AND la.from_date <= ? AND la.to_date >= ? " +
            "WHERE sec.school_id = ? AND t.day_of_week = ? " +
            "  AND t.effective_from <= ? AND COALESCE(t.effective_to, 'infinity'::date) >= ? " +
            "ORDER BY t.period_no",
            (rs, i) -> new Need(
                UUID.fromString(rs.getString("slot_id")),
                UUID.fromString(rs.getString("section_id")),
                rs.getString("section_label"),
                UUID.fromString(rs.getString("subject_id")),
                rs.getString("subject_name"),
                rs.getInt("period_no"), rs.getTime("starts_at"), rs.getTime("ends_at"), rs.getString("room"),
                UUID.fromString(rs.getString("teacher_staff_id")),
                rs.getString("absent_name").trim(),
                UUID.fromString(rs.getString("leave_id"))),
            Date.valueOf(onDate), Date.valueOf(onDate), schoolId,
            onDate.getDayOfWeek().getValue(), Date.valueOf(onDate), Date.valueOf(onDate));

        List<CoverNeedDto> out = new ArrayList<>();
        for (Need need : needs) {
            var covers = jdbc.query(COVER_SELECT + "WHERE c.slot_id = ? AND c.on_date = ? AND c.cancelled_at IS NULL",
                COVER_MAPPER, need.slotId(), Date.valueOf(onDate));
            TimetableCoverDto cover = covers.isEmpty() ? null : covers.get(0);
            out.add(new CoverNeedDto(
                need.slotId(), need.sectionId(), need.sectionLabel(), need.subjectId(), need.subjectName(),
                onDate, need.periodNo(), need.startsAt().toLocalTime(), need.endsAt().toLocalTime(), need.room(),
                need.absentStaffId(), need.absentName(), need.leaveId(), cover,
                cover != null ? List.of()
                    : candidates(schoolId, onDate, need.absentStaffId(), need.startsAt(), need.endsAt())));
        }
        return out;
    }

    /** Teaching staff with nothing timetabled against this period, least-loaded first. */
    private List<CoverNeedDto.CandidateDto> candidates(UUID schoolId, LocalDate onDate, UUID absentStaffId,
                                                       java.sql.Time startsAt, java.sql.Time endsAt) {
        int dayOfWeek = onDate.getDayOfWeek().getValue();
        return jdbc.query(
            "SELECT s.id, (s.first_name || ' ' || COALESCE(s.last_name, '')) AS name, " +
            "       (SELECT count(*) FROM timetable_slot l WHERE l.teacher_staff_id = s.id " +
            "          AND l.day_of_week = ? AND l.effective_from <= ? " +
            "          AND COALESCE(l.effective_to, 'infinity'::date) >= ?) AS periods_that_day " +
            "FROM staff s " +
            "WHERE s.school_id = ? AND s.is_active AND s.id <> ? " +
            "  AND EXISTS (SELECT 1 FROM staff_role r WHERE r.staff_id = s.id AND r.revoked_at IS NULL " +
            "                AND r.role_code IN ('class_teacher','subject_teacher','academic_coordinator')) " +
            "  AND NOT EXISTS (SELECT 1 FROM timetable_slot b WHERE b.teacher_staff_id = s.id " +
            "                    AND b.day_of_week = ? AND b.starts_at < ? AND b.ends_at > ? " +
            "                    AND b.effective_from <= ? AND COALESCE(b.effective_to, 'infinity'::date) >= ?) " +
            "  AND NOT EXISTS (SELECT 1 FROM timetable_cover c JOIN timetable_slot ct ON ct.id = c.slot_id " +
            "                    AND c.substitute_staff_id = s.id AND c.on_date = ? AND c.cancelled_at IS NULL " +
            "                    AND ct.starts_at < ? AND ct.ends_at > ?) " +
            "  AND NOT EXISTS (SELECT 1 FROM leave_application la WHERE la.subject_type = 'staff' " +
            "                    AND la.subject_id = s.id AND la.status = 'approved' " +
            "                    AND la.from_date <= ? AND la.to_date >= ?) " +
            "ORDER BY periods_that_day, name LIMIT 5",
            (rs, i) -> new CoverNeedDto.CandidateDto(
                UUID.fromString(rs.getString("id")), rs.getString("name").trim(),
                rs.getInt("periods_that_day")),
            dayOfWeek, Date.valueOf(onDate), Date.valueOf(onDate),
            schoolId, absentStaffId,
            dayOfWeek, endsAt, startsAt, Date.valueOf(onDate), Date.valueOf(onDate),
            Date.valueOf(onDate), endsAt, startsAt,
            Date.valueOf(onDate), Date.valueOf(onDate));
    }
}
