package com.schoolsoft.fees.internal;

import com.schoolsoft.fees.api.AdmissionFeeStatusDto;
import com.schoolsoft.platform.time.SchoolClock;
import com.schoolsoft.schoolcalendar.api.WorkingDayService;
import com.schoolsoft.tenancy.api.NumberSeries;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The invoice an applicant's admission fee sits on. Declares no transaction of
 * its own: raising is a step of the funnel move that asked for it, and must
 * roll back with it.
 */
@Service
public class AdmissionFeeInvoices {

    /** The head the fee is booked under, made on first use so no school has to set it up to charge one. */
    static final String HEAD_CODE = "ADMISSION";

    private final JdbcTemplate jdbc;
    private final NumberSeries numbers;
    private final WorkingDayService workingDays;
    private final SchoolClock clock;

    public AdmissionFeeInvoices(JdbcTemplate jdbc, NumberSeries numbers, WorkingDayService workingDays,
                                SchoolClock clock) {
        this.jdbc = jdbc;
        this.numbers = numbers;
        this.workingDays = workingDays;
        this.clock = clock;
    }

    public AdmissionFeeStatusDto raise(UUID schoolId, UUID applicationId, String applicationNo,
                                       String applicantName, double amount) {
        var existing = statusFor(applicationId);
        if (existing.isPresent()) return existing.get();
        if (amount <= 0) throw new IllegalArgumentException("An admission fee needs a positive amount");

        record Head(UUID id, double gstRatePct) {}
        Head head = jdbc.query("SELECT id, gst_rate_pct FROM fee_head WHERE school_id = ? AND code = ?",
            (rs, i) -> new Head(UUID.fromString(rs.getString("id")), rs.getDouble("gst_rate_pct")),
            schoolId, HEAD_CODE).stream().findFirst().orElseGet(() -> {
                UUID id = UUID.randomUUID();
                // Not recurring: it is owed once, whole, and no cycle shares it out.
                jdbc.update("INSERT INTO fee_head (id, school_id, code, name, is_recurring, gst_rate_pct) "
                    + "VALUES (?, ?, ?, 'Admission fee', FALSE, 0)", id, schoolId, HEAD_CODE);
                return new Head(id, 0);
            });

        InvoicePricing.PricedLine line = InvoicePricing.line(amount, head.gstRatePct(), 0, 0);
        InvoicePricing.Priced priced = InvoicePricing.invoice(java.util.List.of(line));

        // Booked in the year the money arrives, the same resolution a manually
        // raised invoice uses: an application for next year is paid for now.
        UUID academicYearId = jdbc.query(
            "SELECT id FROM academic_year WHERE school_id = ? AND is_current LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString("id")), schoolId).stream().findFirst().orElse(null);

        LocalDate today = clock.today(schoolId);
        LocalDate due = workingDays.nextWorkingDayOnOrAfter(schoolId, today, null, null);
        UUID invoiceId = UUID.randomUUID();
        String invoiceNo = numbers.next(schoolId, NumberSeries.Kind.invoice, null, "INV{YY}{SEQ:5}", null);
        jdbc.update(
            "INSERT INTO fee_invoice (id, school_id, admission_application_id, academic_year_id, invoice_no, " +
            "  cycle_label, issued_on, due_on, subtotal, gst, total, status) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open')",
            invoiceId, schoolId, applicationId, academicYearId, invoiceNo, "Admission " + applicationNo,
            Date.valueOf(today), Date.valueOf(due), priced.subtotal(), priced.gst(), priced.total());
        jdbc.update(
            "INSERT INTO fee_invoice_line (id, fee_invoice_id, fee_head_id, description, amount, discount, " +
            "  gst, source) VALUES (?, ?, ?, ?, ?, 0, ?, 'manual')",
            UUID.randomUUID(), invoiceId, head.id(), "Admission fee — " + applicantName, line.amount(), line.gst());
        return statusFor(applicationId).orElseThrow();
    }

    public Optional<AdmissionFeeStatusDto> statusFor(UUID applicationId) {
        return jdbc.query(
            "SELECT id, invoice_no, total, paid, status FROM fee_invoice " +
            "WHERE admission_application_id = ? AND status <> 'cancelled'",
            (rs, i) -> new AdmissionFeeStatusDto(UUID.fromString(rs.getString("id")), rs.getString("invoice_no"),
                rs.getDouble("total"), rs.getDouble("paid"), rs.getString("status")),
            applicationId).stream().findFirst();
    }

    public void attachToStudent(UUID applicationId, UUID studentId) {
        jdbc.update(
            "UPDATE fee_invoice_line SET student_id = ? WHERE fee_invoice_id IN " +
            "  (SELECT id FROM fee_invoice WHERE admission_application_id = ?)", studentId, applicationId);
        jdbc.update("UPDATE fee_invoice SET student_id = ?, updated_at = now() " +
            "WHERE admission_application_id = ? AND student_id IS NULL", studentId, applicationId);
    }
}
