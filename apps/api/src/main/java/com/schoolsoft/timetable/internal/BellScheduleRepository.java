package com.schoolsoft.timetable.internal;

import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.timetable.api.BellScheduleDto;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bell schedules and their periods (GAP-12). */
@Repository
public class BellScheduleRepository {

    private final JdbcTemplate jdbc;

    public BellScheduleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<BellScheduleDto> list(UUID schoolId) {
        return jdbc.query(
            "SELECT id, school_id, campus_id, code, name, effective_from, effective_to " +
            "FROM bell_schedule WHERE school_id = ? ORDER BY code",
            (rs, i) -> hydrate(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("school_id")),
                rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id")),
                rs.getString("code"), rs.getString("name"),
                rs.getDate("effective_from").toLocalDate(),
                rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate()),
            schoolId);
    }

    public BellScheduleDto find(UUID id) {
        var rows = jdbc.query(
            "SELECT id, school_id, campus_id, code, name, effective_from, effective_to " +
            "FROM bell_schedule WHERE id = ?",
            (rs, i) -> hydrate(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("school_id")),
                rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id")),
                rs.getString("code"), rs.getString("name"),
                rs.getDate("effective_from").toLocalDate(),
                rs.getDate("effective_to") == null ? null : rs.getDate("effective_to").toLocalDate()),
            id);
        if (rows.isEmpty()) throw new NotFoundException("Bell schedule not found: " + id);
        return rows.get(0);
    }

    private BellScheduleDto hydrate(UUID id, UUID schoolId, UUID campusId, String code, String name,
                                    LocalDate from, LocalDate to) {
        List<BellScheduleDto.Period> periods = jdbc.query(
            "SELECT id, period_no, label, starts_at, ends_at, is_break FROM bell_period " +
            "WHERE bell_schedule_id = ? ORDER BY period_no",
            (rs, i) -> new BellScheduleDto.Period(
                UUID.fromString(rs.getString("id")), rs.getInt("period_no"), rs.getString("label"),
                rs.getTime("starts_at").toLocalTime(), rs.getTime("ends_at").toLocalTime(),
                rs.getBoolean("is_break")),
            id);
        List<UUID> grades = jdbc.query(
            "SELECT grade_id FROM grade_bell_schedule WHERE bell_schedule_id = ?",
            (rs, i) -> UUID.fromString(rs.getString("grade_id")), id);
        return new BellScheduleDto(id, schoolId, campusId, code, name, from, to, grades, periods);
    }

    public record PeriodInput(int periodNo, String label, LocalTime startsAt, LocalTime endsAt, boolean isBreak) {}

    /**
     * Periods may not overlap each other: a school day where period 3 starts
     * before period 2 ends is a typo, and it would make every clash check
     * downstream meaningless.
     */
    public BellScheduleDto create(UUID schoolId, UUID campusId, String code, String name,
                                  LocalDate effectiveFrom, LocalDate effectiveTo,
                                  List<PeriodInput> periods, List<UUID> gradeIds) {
        List<PeriodInput> ordered = periods.stream()
            .sorted(java.util.Comparator.comparing(PeriodInput::startsAt)).toList();
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).startsAt().isBefore(ordered.get(i - 1).endsAt())) {
                throw new IllegalArgumentException(
                    "Periods overlap: " + ordered.get(i - 1).label() + " ends at "
                    + ordered.get(i - 1).endsAt() + " but " + ordered.get(i).label()
                    + " starts at " + ordered.get(i).startsAt());
            }
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO bell_schedule (id, school_id, campus_id, code, name, effective_from, effective_to) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, campusId, code, name,
            java.sql.Date.valueOf(effectiveFrom),
            effectiveTo == null ? null : java.sql.Date.valueOf(effectiveTo));
        for (PeriodInput period : periods) {
            jdbc.update(
                "INSERT INTO bell_period (id, bell_schedule_id, period_no, label, starts_at, ends_at, is_break) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, period.periodNo(), period.label(),
                Time.valueOf(period.startsAt()), Time.valueOf(period.endsAt()), period.isBreak());
        }
        for (UUID gradeId : gradeIds == null ? List.<UUID>of() : gradeIds) {
            jdbc.update(
                "INSERT INTO grade_bell_schedule (grade_id, bell_schedule_id) VALUES (?, ?) " +
                "ON CONFLICT (grade_id) DO UPDATE SET bell_schedule_id = EXCLUDED.bell_schedule_id",
                gradeId, id);
        }
        return find(id);
    }

    /** The schedule a section follows, through its grade. */
    public BellScheduleDto forSection(UUID sectionId) {
        var rows = jdbc.query(
            "SELECT gbs.bell_schedule_id FROM section s " +
            "JOIN grade_bell_schedule gbs ON gbs.grade_id = s.grade_id WHERE s.id = ?",
            (rs, i) -> UUID.fromString(rs.getString("bell_schedule_id")), sectionId);
        if (rows.isEmpty()) {
            throw new NotFoundException("No bell schedule is bound to this section's grade: " + sectionId);
        }
        return find(rows.get(0));
    }
}
