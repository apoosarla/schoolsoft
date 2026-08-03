package com.schoolsoft.timetable.internal;

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
    public TimetableRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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

    /** Throws {@link IllegalArgumentException} (mapped to 400 by GlobalExceptionHandler) if the teacher already has an overlapping slot. */
    public TimetableSlotDto createSlot(
        UUID sectionId, UUID subjectId, UUID teacherStaffId, int dayOfWeek, int periodNo,
        LocalTime startsAt, LocalTime endsAt, String room, LocalDate effectiveFrom, LocalDate effectiveTo
    ) {
        Integer clashCount = jdbc.queryForObject(
            "SELECT count(*) FROM timetable_slot WHERE teacher_staff_id = ? AND day_of_week = ? " +
            "  AND starts_at < ? AND ends_at > ? " +
            "  AND effective_from <= COALESCE(?, 'infinity'::date) AND COALESCE(effective_to, 'infinity'::date) >= ?",
            Integer.class,
            teacherStaffId, dayOfWeek, Time.valueOf(endsAt), Time.valueOf(startsAt),
            effectiveTo == null ? null : Date.valueOf(effectiveTo), Date.valueOf(effectiveFrom)
        );
        if (clashCount != null && clashCount > 0) {
            throw new IllegalArgumentException("Teacher already has an overlapping timetable slot on day " + dayOfWeek);
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO timetable_slot (id, section_id, subject_id, teacher_staff_id, day_of_week, period_no, " +
            "  starts_at, ends_at, room, effective_from, effective_to) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, sectionId, subjectId, teacherStaffId, dayOfWeek, periodNo,
            Time.valueOf(startsAt), Time.valueOf(endsAt), room, Date.valueOf(effectiveFrom),
            effectiveTo == null ? null : Date.valueOf(effectiveTo)
        );
        return jdbc.queryForObject(SELECT + "WHERE t.id = ?", MAPPER, id);
    }

    public void deleteSlot(UUID id) {
        jdbc.update("DELETE FROM timetable_slot WHERE id = ?", id);
    }
}
