package com.schoolsoft.fees.internal;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The two reports a school office actually runs (FEE-14, FEE-15): what came in
 * today, and who still owes.
 *
 * The day book reconciles against the ledger on purpose — the collection total
 * and the ledger's bank debits for the same range are computed separately and
 * returned together, so a mismatch is visible in the report itself rather than
 * discovered at year end.
 */
@Repository
public class FeeReportRepository {

    private final JdbcTemplate jdbc;

    public FeeReportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> dayBook(UUID schoolId, LocalDate from, LocalDate to) {
        List<Map<String, Object>> byMethod = jdbc.query(
            "SELECT COALESCE(p.method, p.gateway) AS method, count(*) AS count, sum(p.amount) AS amount " +
            // Counted by when the money arrived, not by the payment's current
            // status: a cheque that bounced next week was still banked today,
            // and the reversal shows separately below.
            "FROM payment p WHERE p.school_id = ? AND p.captured_at IS NOT NULL " +
            "  AND p.captured_at::date BETWEEN ? AND ? " +
            "GROUP BY COALESCE(p.method, p.gateway) ORDER BY 1",
            (rs, i) -> Map.<String, Object>of(
                "method", rs.getString("method") == null ? "unknown" : rs.getString("method"),
                "count", rs.getInt("count"),
                "amount", rs.getDouble("amount")),
            schoolId, Date.valueOf(from), Date.valueOf(to));

        Double collected = jdbc.queryForObject(
            "SELECT COALESCE(sum(amount), 0) FROM payment WHERE school_id = ? AND captured_at IS NOT NULL " +
            "  AND captured_at::date BETWEEN ? AND ?",
            Double.class, schoolId, Date.valueOf(from), Date.valueOf(to));

        // The same money, seen from the ledger: bank debits less bank credits
        // (a reversal or refund puts money back out).
        Double ledgerNet = jdbc.queryForObject(
            "SELECT COALESCE(sum(debit) - sum(credit), 0) FROM ledger_entry " +
            "WHERE school_id = ? AND account_code = 'BANK' AND posted_at::date BETWEEN ? AND ?",
            Double.class, schoolId, Date.valueOf(from), Date.valueOf(to));

        Double refunded = jdbc.queryForObject(
            "SELECT COALESCE(sum(amount), 0) FROM fee_adjustment " +
            "WHERE school_id = ? AND kind IN ('refund','reversal') AND created_at::date BETWEEN ? AND ?",
            Double.class, schoolId, Date.valueOf(from), Date.valueOf(to));

        double collectedValue = collected == null ? 0 : collected;
        double refundedValue = refunded == null ? 0 : refunded;
        double ledgerValue = ledgerNet == null ? 0 : ledgerNet;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("from", from);
        report.put("to", to);
        report.put("collected", round(collectedValue));
        report.put("refunded", round(refundedValue));
        report.put("net", round(collectedValue - refundedValue));
        report.put("ledgerNet", round(ledgerValue));
        report.put("reconciles", Math.abs(round(collectedValue - refundedValue) - round(ledgerValue)) < 0.01);
        report.put("byMethod", byMethod);
        return report;
    }

    /**
     * Outstanding dues, aggregated at the level the caller asks for. Year-end
     * clearance reads the student level of this; a principal reads the grade
     * level of the same numbers, so the two never disagree.
     */
    public Map<String, Object> outstanding(UUID schoolId, UUID academicYearId, UUID gradeId, UUID sectionId) {
        StringBuilder where = new StringBuilder(
            "WHERE fi.school_id = ? AND fi.status IN ('open','partial','overdue') AND fi.total > fi.paid " +
            "  AND fi.student_id IS NOT NULL");
        List<Object> args = new java.util.ArrayList<>();
        args.add(schoolId);
        if (academicYearId != null) { where.append(" AND e.academic_year_id = ?"); args.add(academicYearId); }
        if (gradeId != null) { where.append(" AND sec.grade_id = ?"); args.add(gradeId); }
        if (sectionId != null) { where.append(" AND sec.id = ?"); args.add(sectionId); }

        String from =
            "FROM fee_invoice fi " +
            "JOIN student st ON st.id = fi.student_id " +
            "LEFT JOIN enrolment e ON e.student_id = st.id AND e.status = 'active' " +
            "LEFT JOIN section sec ON sec.id = e.section_id " +
            "LEFT JOIN grade g ON g.id = sec.grade_id ";

        List<Map<String, Object>> students = jdbc.query(
            "SELECT st.id, st.admission_no, (st.first_name || ' ' || COALESCE(st.last_name, '')) AS name, " +
            "       g.code AS grade_code, sec.code AS section_code, " +
            "       sum(fi.total - fi.paid) AS balance, count(*) AS invoices, min(fi.due_on) AS oldest_due " +
            from + where +
            " GROUP BY st.id, st.admission_no, st.first_name, st.last_name, g.code, sec.code " +
            " ORDER BY sum(fi.total - fi.paid) DESC",
            (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("studentId", rs.getString("id"));
                row.put("admissionNo", rs.getString("admission_no"));
                row.put("name", rs.getString("name").trim());
                row.put("gradeCode", rs.getString("grade_code"));
                row.put("sectionCode", rs.getString("section_code"));
                row.put("balance", round(rs.getDouble("balance")));
                row.put("invoices", rs.getInt("invoices"));
                row.put("oldestDueOn", rs.getDate("oldest_due").toLocalDate().toString());
                return row;
            },
            args.toArray());

        List<Map<String, Object>> byGrade = jdbc.query(
            "SELECT COALESCE(g.code, '(unassigned)') AS grade_code, count(DISTINCT st.id) AS students, " +
            "       sum(fi.total - fi.paid) AS balance " +
            from + where + " GROUP BY g.code ORDER BY 1",
            (rs, i) -> Map.<String, Object>of(
                "gradeCode", rs.getString("grade_code"),
                "students", rs.getInt("students"),
                "balance", round(rs.getDouble("balance"))),
            args.toArray());

        double total = students.stream().mapToDouble(row -> (Double) row.get("balance")).sum();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("totalOutstanding", round(total));
        report.put("studentsWithDues", students.size());
        report.put("byGrade", byGrade);
        report.put("students", students);
        return report;
    }

    /** Does this student owe anything? The predicate year-end clearance asks. */
    public Map<String, Object> duesFor(UUID studentId) {
        Double balance = jdbc.queryForObject(
            "SELECT COALESCE(sum(total - paid), 0) FROM fee_invoice " +
            "WHERE student_id = ? AND status IN ('open','partial','overdue')",
            Double.class, studentId);
        double value = balance == null ? 0 : balance;
        return Map.of("studentId", studentId, "balance", round(value), "hasDues", value > 0.005);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
