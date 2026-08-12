package com.schoolsoft.tenancy.api;

import com.schoolsoft.platform.web.ForbiddenException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Refuses writes against a closed academic year (GAP-14).
 *
 * Called from the write paths that carry a year's worth of consequence —
 * attendance, marks, fees — rather than being enforced per controller, so a new
 * endpoint on any of those tables inherits the rule by calling the same
 * repository method. A closed year is reopened explicitly and audibly through
 * {@link SchoolController}, never as a side effect of a write.
 *
 * A date that falls in no academic year is allowed through: that is a data
 * problem for the tenancy module to surface, not a reason to block a teacher
 * from marking today's attendance.
 */
@Service
public class AcademicYearGuard {

    private final JdbcTemplate jdbc;

    public AcademicYearGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Throws if the given academic year is closed. */
    public void requireOpen(UUID academicYearId) {
        if (academicYearId == null) return;
        List<StatusRow> rows = jdbc.query(
            "SELECT id, code, status FROM academic_year WHERE id = ?", STATUS, academicYearId);
        if (rows.isEmpty()) return;
        refuseIfClosed(rows.get(0));
    }

    /** Throws if the academic year containing {@code date} for this school is closed. */
    public void requireOpenOn(UUID schoolId, LocalDate date) {
        if (schoolId == null || date == null) return;
        List<StatusRow> rows = jdbc.query(
            "SELECT id, code, status FROM academic_year " +
            "WHERE school_id = ? AND ? BETWEEN starts_on AND ends_on",
            STATUS, schoolId, Date.valueOf(date));
        if (rows.isEmpty()) return;
        refuseIfClosed(rows.get(0));
    }

    /** Throws if the academic year the section belongs to is closed. */
    public void requireOpenForSection(UUID sectionId) {
        if (sectionId == null) return;
        List<StatusRow> rows = jdbc.query(
            "SELECT ay.id, ay.code, ay.status FROM academic_year ay " +
            "JOIN section s ON s.academic_year_id = ay.id WHERE s.id = ?",
            STATUS, sectionId);
        if (rows.isEmpty()) return;
        refuseIfClosed(rows.get(0));
    }

    /** Throws if the academic year the assessment component's section belongs to is closed. */
    public void requireOpenForAssessmentComponent(UUID componentId) {
        if (componentId == null) return;
        List<StatusRow> rows = jdbc.query(
            "SELECT ay.id, ay.code, ay.status FROM academic_year ay " +
            "JOIN section s ON s.academic_year_id = ay.id " +
            "JOIN assessment a ON a.section_id = s.id " +
            "JOIN assessment_component ac ON ac.assessment_id = a.id WHERE ac.id = ?",
            STATUS, componentId);
        if (rows.isEmpty()) return;
        refuseIfClosed(rows.get(0));
    }

    /** Throws if the academic year the invoice was issued into is closed. */
    public void requireOpenForInvoice(UUID invoiceId) {
        if (invoiceId == null) return;
        List<StatusRow> rows = jdbc.query(
            "SELECT ay.id, ay.code, ay.status FROM academic_year ay " +
            "JOIN fee_invoice fi ON fi.school_id = ay.school_id " +
            "  AND fi.issued_on BETWEEN ay.starts_on AND ay.ends_on WHERE fi.id = ?",
            STATUS, invoiceId);
        if (rows.isEmpty()) return;
        refuseIfClosed(rows.get(0));
    }

    private void refuseIfClosed(StatusRow row) {
        if ("closed".equals(row.status())) {
            throw new ForbiddenException(
                "Academic year " + row.code() + " is closed; reopen it before editing its records");
        }
    }

    private record StatusRow(UUID id, String code, String status) {}

    private static final org.springframework.jdbc.core.RowMapper<StatusRow> STATUS = (rs, i) ->
        new StatusRow(UUID.fromString(rs.getString("id")), rs.getString("code"), rs.getString("status"));
}
