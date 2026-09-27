package com.schoolsoft.dashboard.internal;

import com.schoolsoft.dashboard.api.SchoolOverviewDto;
import com.schoolsoft.platform.time.SchoolClock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {

    private final JdbcTemplate jdbc;
    private final SchoolClock clock;

    public DashboardRepository(JdbcTemplate jdbc, SchoolClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * {@code includeFees} and {@code includeAdmissions} are the caller's
     * answer, decided at the controller; a section left out is {@code null},
     * not zero, and is not queried at all.
     */
    public SchoolOverviewDto overview(UUID schoolId, boolean includeFees, boolean includeAdmissions) {
        // The denominator today's attendance percentage divides by, so it has
        // to be the register today. Read as a status it shrank the moment a
        // withdrawal was filed while the child was still being marked present,
        // which showed the school an attendance rate over 100%.
        java.time.LocalDate day = clock.today(schoolId);
        java.sql.Date today = java.sql.Date.valueOf(day);
        java.sql.Date monthStart = java.sql.Date.valueOf(day.withDayOfMonth(1));
        long activeEnrolments = jdbc.queryForObject(
            "SELECT count(*) FROM enrolment e WHERE e.school_id = ? AND "
                + com.schoolsoft.enrolment.api.EnrolmentActivity.activeOn("e"),
            Long.class, schoolId, today, today
        );

        long presentToday = jdbc.queryForObject(
            "SELECT count(*) FROM attendance_record WHERE school_id = ? AND on_date = ? " +
            "  AND period_no IS NULL AND status = 'present'",
            Long.class, schoolId, today
        );
        // Nothing marked yet is not 0% attendance — it is no answer yet, and a
        // head reading "0%" at 8 a.m. (or on a Saturday) reads an emergency.
        long markedToday = jdbc.queryForObject(
            "SELECT count(*) FROM attendance_record WHERE school_id = ? AND on_date = ? " +
            "  AND period_no IS NULL AND voided_at IS NULL",
            Long.class, schoolId, today
        );
        Double attendanceTodayPct = activeEnrolments == 0 || markedToday == 0
            ? null : (presentToday * 100.0 / activeEnrolments);

        Double feeInvoicedMtd = null, feeCollectedMtd = null, feeCollectionMtdPct = null;
        if (includeFees) {
            feeInvoicedMtd = jdbc.queryForObject(
                "SELECT COALESCE(sum(total), 0) FROM fee_invoice WHERE school_id = ? " +
                "  AND issued_on >= ?",
                Double.class, schoolId, monthStart
            );
            feeCollectedMtd = jdbc.queryForObject(
                "SELECT COALESCE(sum(paid), 0) FROM fee_invoice WHERE school_id = ? " +
                "  AND issued_on >= ?",
                Double.class, schoolId, monthStart
            );
            feeCollectionMtdPct = feeInvoicedMtd == 0 ? null : (feeCollectedMtd * 100.0 / feeInvoicedMtd);
        }

        Map<String, Long> admissionsFunnel = null;
        if (includeAdmissions) {
            Map<String, Long> funnel = new LinkedHashMap<>();
            jdbc.query(
                "SELECT state, count(*) AS n FROM admission_application WHERE school_id = ? " +
                "GROUP BY state ORDER BY state",
                rs -> { funnel.put(rs.getString("state"), rs.getLong("n")); },
                schoolId
            );
            admissionsFunnel = funnel;
        }

        long announcementsPublished30d = jdbc.queryForObject(
            "SELECT count(*) FROM announcement WHERE school_id = ? AND published_at >= now() - interval '30 days'",
            Long.class, schoolId
        );
        long announcementReads30d = jdbc.queryForObject(
            "SELECT count(*) FROM announcement_read ar JOIN announcement a ON a.id = ar.announcement_id " +
            "  WHERE a.school_id = ? AND ar.read_at >= now() - interval '30 days'",
            Long.class, schoolId
        );

        return new SchoolOverviewDto(
            activeEnrolments, presentToday, attendanceTodayPct,
            feeInvoicedMtd, feeCollectedMtd, feeCollectionMtdPct,
            admissionsFunnel, announcementsPublished30d, announcementReads30d
        );
    }
}
