package com.schoolsoft.rollover.internal;

import com.schoolsoft.rollover.api.ReadinessReportDto;
import com.schoolsoft.schoolcalendar.api.WorkingDayService;
import com.schoolsoft.tenancy.api.AcademicYearDto;
import com.schoolsoft.tenancy.api.AcademicYearLifecycle;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The year-end readiness check (YEC-01).
 *
 * Five questions, each answered by the module that owns it: are the
 * assessments published, are the report cards sealed, has every child a
 * promotion decision, is the register complete, and does anybody still owe
 * money. A school that closes its year with any of these outstanding discovers
 * it months later, when the year is read-only and the fix needs a reopen.
 *
 * The unmarked-days query is deliberately per (section, date) rather than per
 * student: a register is marked or it is not, and counting 2,000 children on a
 * missing day would report a number nobody can act on.
 */
@Service
public class RolloverReadiness {

    /** Enough for the office to work from; more than this is a report, not a list. */
    private static final int ITEMS_PER_KIND = 25;

    private final JdbcTemplate jdbc;
    private final WorkingDayService workingDays;
    private final AcademicYearLifecycle academicYears;

    public RolloverReadiness(JdbcTemplate jdbc, WorkingDayService workingDays,
                             AcademicYearLifecycle academicYears) {
        this.jdbc = jdbc;
        this.workingDays = workingDays;
        this.academicYears = academicYears;
    }

    public ReadinessReportDto check(UUID schoolId, UUID academicYearId) {
        AcademicYearDto year = academicYears.find(academicYearId);
        List<ReadinessReportDto.Item> items = new ArrayList<>();

        int activeEnrolments = intOf(
            "SELECT count(*) FROM enrolment WHERE academic_year_id = ? AND status = 'active'",
            academicYearId);

        int unpublished = collect(items, "unpublished_assessment",
            "SELECT a.id, (g.code || '-' || s.code || ' · ' || sub.name || ' · ' || a.name " +
            "        || ' (' || a.status || ')') AS detail " +
            "FROM assessment a " +
            "JOIN section s ON s.id = a.section_id " +
            "JOIN grade g ON g.id = s.grade_id " +
            "JOIN subject sub ON sub.id = a.subject_id " +
            "WHERE s.academic_year_id = ? AND a.status <> 'published' " +
            "ORDER BY g.sort_order, s.code, sub.name",
            academicYearId);

        int unlockedCards = collect(items, "unlocked_report_card",
            "SELECT rc.id, (st.admission_no || ' · ' || st.first_name || ' ' " +
            "        || COALESCE(st.last_name, '') || ' (' || rc.status || ')') AS detail " +
            "FROM report_card rc JOIN student st ON st.id = rc.student_id " +
            "WHERE rc.academic_year_id = ? AND rc.status NOT IN ('locked','published') " +
            "ORDER BY st.admission_no",
            academicYearId);

        // A child with no decision is the one that stops a rollover dead: there
        // is nothing to move them by, and guessing 'promote' is how a detained
        // child ends up a grade ahead of the class they were meant to repeat.
        int missingDecisions = collect(items, "missing_promotion_decision",
            "SELECT st.id, (st.admission_no || ' · ' || st.first_name || ' ' " +
            "        || COALESCE(st.last_name, '')) AS detail " +
            "FROM enrolment e JOIN student st ON st.id = e.student_id " +
            "WHERE e.academic_year_id = ? AND e.status = 'active' " +
            "  AND NOT EXISTS (SELECT 1 FROM report_card rc " +
            "                  WHERE rc.student_id = e.student_id " +
            "                    AND rc.academic_year_id = e.academic_year_id " +
            "                    AND rc.promotion_decision IS NOT NULL) " +
            "ORDER BY st.admission_no",
            academicYearId);

        var unmarked = unmarkedDays(schoolId, year);
        for (int i = 0; i < Math.min(unmarked.size(), ITEMS_PER_KIND); i++) {
            items.add(new ReadinessReportDto.Item("unmarked_attendance_day",
                unmarked.get(i).label(), unmarked.get(i).sectionId()));
        }

        int studentsWithDues = collect(items, "outstanding_dues",
            "SELECT st.id, (st.admission_no || ' · ' || st.first_name || ' ' " +
            "        || COALESCE(st.last_name, '') || ' — ' " +
            "        || to_char(sum(fi.total - fi.paid), 'FM999999990.00')) AS detail " +
            "FROM fee_invoice fi JOIN student st ON st.id = fi.student_id " +
            "JOIN enrolment e ON e.student_id = st.id AND e.academic_year_id = ? AND e.status = 'active' " +
            "WHERE fi.school_id = ? AND fi.status IN ('open','partial','overdue') AND fi.total > fi.paid " +
            "GROUP BY st.id, st.admission_no, st.first_name, st.last_name " +
            "ORDER BY sum(fi.total - fi.paid) DESC",
            academicYearId, schoolId);

        Double outstanding = jdbc.queryForObject(
            "SELECT COALESCE(sum(fi.total - fi.paid), 0) FROM fee_invoice fi " +
            "JOIN enrolment e ON e.student_id = fi.student_id AND e.academic_year_id = ? AND e.status = 'active' " +
            "WHERE fi.school_id = ? AND fi.status IN ('open','partial','overdue') AND fi.total > fi.paid",
            Double.class, academicYearId, schoolId);

        boolean ready = unpublished == 0 && unlockedCards == 0 && missingDecisions == 0
            && unmarked.isEmpty() && studentsWithDues == 0;

        return new ReadinessReportDto(schoolId, academicYearId, year.code(), ready,
            activeEnrolments, unpublished, unlockedCards, missingDecisions, unmarked.size(),
            studentsWithDues, outstanding == null ? 0 : Math.round(outstanding * 100.0) / 100.0,
            items);
    }

    private record UnmarkedDay(UUID sectionId, String label) {}

    /**
     * Working days in the year, up to today, on which a section with children
     * in it has no register at all. The working-day calendar is the authority
     * (Phase 1), so a holiday or a declared closure never shows up here.
     */
    private List<UnmarkedDay> unmarkedDays(UUID schoolId, AcademicYearDto year) {
        LocalDate to = year.endsOn().isBefore(LocalDate.now()) ? year.endsOn() : LocalDate.now();
        if (to.isBefore(year.startsOn())) return List.of();
        List<LocalDate> days = workingDays.workingDays(schoolId, year.startsOn(), to, null, null);
        if (days.isEmpty()) return List.of();

        var sections = jdbc.query(
            "SELECT s.id, (g.code || '-' || s.code) AS label FROM section s " +
            "JOIN grade g ON g.id = s.grade_id " +
            "WHERE s.academic_year_id = ? " +
            "  AND EXISTS (SELECT 1 FROM enrolment e WHERE e.section_id = s.id AND e.status = 'active') " +
            "ORDER BY g.sort_order, s.code",
            (rs, i) -> new Object[]{ UUID.fromString(rs.getString("id")), rs.getString("label") },
            year.id());

        List<UnmarkedDay> unmarked = new ArrayList<>();
        for (Object[] section : sections) {
            UUID sectionId = (UUID) section[0];
            var marked = jdbc.queryForList(
                "SELECT DISTINCT on_date FROM attendance_record " +
                "WHERE section_id = ? AND on_date BETWEEN ? AND ?",
                LocalDate.class, sectionId, Date.valueOf(year.startsOn()), Date.valueOf(to));
            var markedSet = new java.util.HashSet<>(marked);
            for (LocalDate day : days) {
                if (!markedSet.contains(day)) {
                    unmarked.add(new UnmarkedDay(sectionId, section[1] + " · " + day));
                }
            }
        }
        return unmarked;
    }

    private int collect(List<ReadinessReportDto.Item> items, String kind, String sql, Object... args) {
        var rows = jdbc.query(sql,
            (rs, i) -> new Object[]{ UUID.fromString(rs.getString(1)), rs.getString("detail") }, args);
        for (int i = 0; i < Math.min(rows.size(), ITEMS_PER_KIND); i++) {
            items.add(new ReadinessReportDto.Item(kind, (String) rows.get(i)[1], (UUID) rows.get(i)[0]));
        }
        return rows.size();
    }

    private int intOf(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
