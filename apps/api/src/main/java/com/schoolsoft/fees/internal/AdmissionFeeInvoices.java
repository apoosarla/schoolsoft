package com.schoolsoft.fees.internal;

import com.schoolsoft.fees.api.AdmissionFeeOwedDto;
import com.schoolsoft.fees.api.AdmissionFeeStatusDto;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.platform.time.SchoolClock;
import com.schoolsoft.schoolcalendar.api.WorkingDayService;
import com.schoolsoft.tenancy.api.NumberSeries;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
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
    private final FeeAdjustmentService adjustments;

    public AdmissionFeeInvoices(JdbcTemplate jdbc, NumberSeries numbers, WorkingDayService workingDays,
                                SchoolClock clock, FeeAdjustmentService adjustments) {
        this.jdbc = jdbc;
        this.adjustments = adjustments;
        this.numbers = numbers;
        this.workingDays = workingDays;
        this.clock = clock;
    }

    public AdmissionFeeStatusDto raise(UUID schoolId, UUID applicationId, String applicationNo,
                                       String applicantName, double amount) {
        var existing = live(applicationId);
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
        return live(applicationId).orElseThrow();
    }

    private static final String STATUS_SQL =
        "SELECT fi.id, fi.invoice_no, fi.total, fi.paid, fi.status, " +
        "  (SELECT COALESCE(sum(a.amount), 0) FROM fee_adjustment a " +
        "    WHERE a.fee_invoice_id = fi.id AND a.kind = 'refund') AS refunded " +
        "FROM fee_invoice fi WHERE fi.admission_application_id = ? ";

    private static final org.springframework.jdbc.core.RowMapper<AdmissionFeeStatusDto> STATUS_MAPPER =
        (rs, i) -> new AdmissionFeeStatusDto(UUID.fromString(rs.getString("id")), rs.getString("invoice_no"),
            rs.getDouble("total"), rs.getDouble("paid"), rs.getString("status"), rs.getDouble("refunded"));

    /** The bill in force: at most one, by {@code fee_invoice_one_per_application}. */
    private Optional<AdmissionFeeStatusDto> live(UUID applicationId) {
        return jdbc.query(STATUS_SQL + "AND fi.status <> 'cancelled'", STATUS_MAPPER, applicationId)
            .stream().findFirst();
    }

    /**
     * The bill in force, or failing that the one that was cancelled when the
     * application closed — so a closed application still says what became of
     * its fee instead of reading as never billed.
     */
    public Optional<AdmissionFeeStatusDto> statusFor(UUID applicationId) {
        var inForce = live(applicationId);
        if (inForce.isPresent()) return inForce;
        return jdbc.query(STATUS_SQL + "ORDER BY fi.created_at DESC LIMIT 1", STATUS_MAPPER, applicationId)
            .stream().findFirst();
    }

    public List<AdmissionFeeOwedDto> owed(UUID schoolId) {
        // student_id IS NULL: once the seat is confirmed the bill is the
        // child's, and the student dues report is where it is chased.
        return jdbc.query(
            "SELECT admission_application_id, id, invoice_no, total, paid FROM fee_invoice " +
            "WHERE school_id = ? AND admission_application_id IS NOT NULL AND student_id IS NULL " +
            "  AND status IN ('open','partial','overdue') AND total > paid ORDER BY due_on",
            (rs, i) -> new AdmissionFeeOwedDto(UUID.fromString(rs.getString("admission_application_id")),
                UUID.fromString(rs.getString("id")), rs.getString("invoice_no"),
                rs.getDouble("total"), rs.getDouble("paid")),
            schoolId);
    }

    /**
     * One conditional UPDATE naming the state it moves out of: only a bill with
     * nothing paid and nothing held is cancelled, so a payment landing at the
     * same moment keeps its invoice.
     */
    public void cancelIfUnpaid(UUID applicationId) {
        jdbc.update(
            "UPDATE fee_invoice SET status = 'cancelled', updated_at = now() " +
            "WHERE admission_application_id = ? AND student_id IS NULL " +
            "  AND status IN ('open','overdue') AND paid = 0 AND advance_amount = 0", applicationId);
    }

    public AdmissionFeeStatusDto refund(UUID schoolId, UUID applicationId, String reason) {
        AdmissionFeeStatusDto fee = live(applicationId).orElseThrow(
            () -> new NotFoundException("This application has no admission fee to refund."));
        record Left(UUID paymentId, double amount) {}
        List<Left> payments = jdbc.query(
            "SELECT p.id, p.amount - COALESCE((SELECT sum(a.amount) FROM fee_adjustment a " +
            "    WHERE a.payment_id = p.id AND a.kind IN ('reversal','refund')), 0) AS left_over " +
            "FROM payment p WHERE p.fee_invoice_id = ? AND p.status = 'captured' ORDER BY p.created_at",
            (rs, i) -> new Left(UUID.fromString(rs.getString("id")), rs.getDouble("left_over")),
            fee.invoiceId());
        payments = payments.stream().filter(p -> p.amount() > 0.005).toList();
        if (payments.isEmpty()) {
            // Not an error to ask twice — but say so, rather than report a
            // refund that moved no money.
            if ("refunded".equals(fee.status())) return fee;
            throw new ConflictException("Nothing has been paid on " + fee.invoiceNo() + ", so there is nothing to refund.");
        }
        for (Left payment : payments) {
            adjustments.adjust(schoolId, fee.invoiceId(), "refund", payment.amount(), reason,
                payment.paymentId(), null, null);
        }
        return live(applicationId).orElseThrow();
    }

    public void attachToStudent(UUID applicationId, UUID studentId) {
        jdbc.update(
            "UPDATE fee_invoice_line SET student_id = ? WHERE fee_invoice_id IN " +
            "  (SELECT id FROM fee_invoice WHERE admission_application_id = ?)", studentId, applicationId);
        jdbc.update("UPDATE fee_invoice SET student_id = ?, updated_at = now() " +
            "WHERE admission_application_id = ? AND student_id IS NULL", studentId, applicationId);
    }
}
