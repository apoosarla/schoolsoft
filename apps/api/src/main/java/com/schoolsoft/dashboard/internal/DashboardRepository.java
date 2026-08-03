package com.schoolsoft.dashboard.internal;

import com.schoolsoft.dashboard.api.SchoolOverviewDto;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {

    private final JdbcTemplate jdbc;
    public DashboardRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public SchoolOverviewDto overview(UUID schoolId) {
        long activeEnrolments = jdbc.queryForObject(
            "SELECT count(*) FROM enrolment WHERE school_id = ? AND status = 'active'", Long.class, schoolId
        );

        long presentToday = jdbc.queryForObject(
            "SELECT count(*) FROM attendance_record WHERE school_id = ? AND on_date = CURRENT_DATE " +
            "  AND period_no IS NULL AND status = 'present'",
            Long.class, schoolId
        );
        Double attendanceTodayPct = activeEnrolments == 0 ? null : (presentToday * 100.0 / activeEnrolments);

        double feeInvoicedMtd = jdbc.queryForObject(
            "SELECT COALESCE(sum(total), 0) FROM fee_invoice WHERE school_id = ? " +
            "  AND issued_on >= date_trunc('month', CURRENT_DATE)::date",
            Double.class, schoolId
        );
        double feeCollectedMtd = jdbc.queryForObject(
            "SELECT COALESCE(sum(paid), 0) FROM fee_invoice WHERE school_id = ? " +
            "  AND issued_on >= date_trunc('month', CURRENT_DATE)::date",
            Double.class, schoolId
        );
        Double feeCollectionMtdPct = feeInvoicedMtd == 0 ? null : (feeCollectedMtd * 100.0 / feeInvoicedMtd);

        Map<String, Long> admissionsFunnel = new LinkedHashMap<>();
        jdbc.query(
            "SELECT state, count(*) AS n FROM admission_application WHERE school_id = ? GROUP BY state ORDER BY state",
            rs -> { admissionsFunnel.put(rs.getString("state"), rs.getLong("n")); },
            schoolId
        );

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
