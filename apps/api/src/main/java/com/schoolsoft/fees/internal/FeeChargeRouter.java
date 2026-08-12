package com.schoolsoft.fees.internal;

import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.NumberSeries;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Finds the invoice an ad-hoc charge belongs on, opening one when there is none. */
@Service
public class FeeChargeRouter {

    private final JdbcTemplate jdbc;
    private final NumberSeries numbers;

    public FeeChargeRouter(JdbcTemplate jdbc, NumberSeries numbers) {
        this.jdbc = jdbc;
        this.numbers = numbers;
    }

    /**
     * The student's newest unpaid invoice, or a fresh "Miscellaneous" one. A
     * fine attached to an already-settled invoice would be invisible to the
     * parent — it has to land somewhere they will be asked to pay.
     */
    public UUID invoiceForCharge(UUID schoolId, UUID studentId, String reason) {
        var open = jdbc.query(
            "SELECT id FROM fee_invoice WHERE student_id = ? AND status IN ('open','partial','overdue') " +
            "ORDER BY due_on DESC LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString("id")), studentId);
        if (!open.isEmpty()) return open.get(0);

        UUID academicYearId = jdbc.query(
            "SELECT id FROM academic_year WHERE school_id = ? AND is_current LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString("id")), schoolId).stream().findFirst().orElse(null);

        UUID invoiceId = UUID.randomUUID();
        String invoiceNo = numbers.next(schoolId, NumberSeries.Kind.invoice, null, "INV{YY}{SEQ:5}", null);
        jdbc.update(
            "INSERT INTO fee_invoice (id, school_id, student_id, academic_year_id, invoice_no, cycle_label, " +
            "  issued_on, due_on, subtotal, gst, total, status) " +
            "VALUES (?, ?, ?, ?, ?, ?, CURRENT_DATE, ?, 0, 0, 0, 'open')",
            invoiceId, schoolId, studentId, academicYearId, invoiceNo,
            "Miscellaneous " + LocalDate.now().getYear(),
            java.sql.Date.valueOf(LocalDate.now().plusDays(15)));
        return invoiceId;
    }

    public UUID feeHeadByCode(UUID schoolId, String code) {
        var rows = jdbc.query("SELECT id FROM fee_head WHERE school_id = ? AND code = ?",
            (rs, i) -> UUID.fromString(rs.getString("id")), schoolId, code);
        if (!rows.isEmpty()) return rows.get(0);
        var fallback = jdbc.query(
            "SELECT id FROM fee_head WHERE school_id = ? ORDER BY (code = 'TUITION') DESC, code LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString("id")), schoolId);
        if (fallback.isEmpty()) throw new NotFoundException("School has no fee heads configured: " + schoolId);
        return fallback.get(0);
    }
}
