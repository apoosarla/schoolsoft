package com.schoolsoft.fees.api;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * What a student still owes, for the modules that have to act on it.
 *
 * Report-card publication is the first such caller (ASMT-15): a school that
 * withholds results over arrears needs one definition of arrears, not the
 * assessment module's own SELECT drifting away from the fee module's.
 *
 * A household bill counts against every child in it — a combined invoice is
 * the family's debt, and withholding one sibling's card because the invoice
 * happens to name the other would be arbitrary.
 */
@Service
public class FeeDues {

    private final JdbcTemplate jdbc;

    public FeeDues(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Outstanding amount across the student's own and their family's live invoices. */
    public double outstandingForStudent(UUID studentId) {
        Double due = jdbc.queryForObject(
            "SELECT COALESCE(SUM(GREATEST(fi.total - fi.paid, 0)), 0) FROM fee_invoice fi " +
            "WHERE fi.status IN ('open','partial','overdue') " +
            "  AND (fi.student_id = ? " +
            "       OR fi.family_id = (SELECT family_id FROM student WHERE id = ? AND family_id IS NOT NULL))",
            Double.class, studentId, studentId);
        return due == null ? 0 : due;
    }
}
