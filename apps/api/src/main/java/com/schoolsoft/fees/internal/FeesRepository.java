package com.schoolsoft.fees.internal;

import com.schoolsoft.fees.api.FeeHeadDto;
import com.schoolsoft.fees.api.FeeInvoiceDto;
import com.schoolsoft.fees.api.FeeInvoiceLineDto;
import com.schoolsoft.fees.api.LedgerEntryDto;
import com.schoolsoft.fees.api.PaymentDto;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.schoolcalendar.api.WorkingDayService;
import com.schoolsoft.tenancy.api.AcademicYearGuard;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class FeesRepository {

    private final JdbcTemplate jdbc;
    private final AcademicYearGuard academicYears;
    private final WorkingDayService workingDays;

    public FeesRepository(JdbcTemplate jdbc, AcademicYearGuard academicYears, WorkingDayService workingDays) {
        this.jdbc = jdbc;
        this.academicYears = academicYears;
        this.workingDays = workingDays;
    }

    // -------------------------- Fee Head --------------------------

    private static final RowMapper<FeeHeadDto> HEAD_MAPPER = (rs, i) -> new FeeHeadDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("code"),
        rs.getString("name"),
        rs.getBoolean("is_recurring"),
        rs.getDouble("gst_rate_pct"),
        rs.getString("hsn_sac")
    );

    public List<FeeHeadDto> listHeads(UUID schoolId) {
        return jdbc.query(
            "SELECT id, school_id, code, name, is_recurring, gst_rate_pct, hsn_sac FROM fee_head WHERE school_id = ? ORDER BY name",
            HEAD_MAPPER, schoolId
        );
    }

    public FeeHeadDto createHead(UUID schoolId, String code, String name, boolean isRecurring, double gstRatePct, String hsnSac) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO fee_head (id, school_id, code, name, is_recurring, gst_rate_pct, hsn_sac) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, code, name, isRecurring, gstRatePct, hsnSac
        );
        return jdbc.queryForObject(
            "SELECT id, school_id, code, name, is_recurring, gst_rate_pct, hsn_sac FROM fee_head WHERE id = ?",
            HEAD_MAPPER, id
        );
    }

    // -------------------------- Invoice --------------------------

    private static final RowMapper<FeeInvoiceDto> INVOICE_MAPPER = (rs, i) -> new FeeInvoiceDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("student_id")),
        rs.getString("invoice_no"),
        rs.getString("cycle_label"),
        rs.getDate("issued_on").toLocalDate(),
        rs.getDate("due_on").toLocalDate(),
        rs.getDouble("subtotal"),
        rs.getDouble("gst"),
        rs.getDouble("total"),
        rs.getDouble("paid"),
        rs.getString("status")
    );

    private static final String INVOICE_COLS =
        "id, school_id, student_id, invoice_no, cycle_label, issued_on, due_on, subtotal, gst, total, paid, status";

    public List<FeeInvoiceDto> listInvoicesByStudent(UUID studentId) {
        return jdbc.query(
            "SELECT " + INVOICE_COLS + " FROM fee_invoice WHERE student_id = ? ORDER BY issued_on DESC",
            INVOICE_MAPPER, studentId
        );
    }

    public Optional<FeeInvoiceDto> findInvoice(UUID id) {
        var rows = jdbc.query("SELECT " + INVOICE_COLS + " FROM fee_invoice WHERE id = ?", INVOICE_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public record InvoiceLineInput(UUID feeHeadId, String description, double amount, double discount, double gst) {}

    public FeeInvoiceDto createInvoice(
        UUID schoolId, UUID studentId, String invoiceNo, String cycleLabel, LocalDate dueOn, List<InvoiceLineInput> lines
    ) {
        // Billing into a closed year is the same mistake as editing its marks.
        academicYears.requireOpenOn(schoolId, LocalDate.now());

        // A due date on a holiday penalises a family for a day the school is
        // shut, so it moves to the next day the office is open (GAP-01). One
        // authority for that, shared with every attendance denominator.
        LocalDate dueOnWorkingDay = workingDays.nextWorkingDayOnOrAfter(schoolId, dueOn, null, null);

        double subtotal = lines.stream().mapToDouble(l -> l.amount() - l.discount()).sum();
        double gst = lines.stream().mapToDouble(InvoiceLineInput::gst).sum();
        double total = subtotal + gst;

        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO fee_invoice (id, school_id, student_id, invoice_no, cycle_label, due_on, subtotal, gst, total) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, studentId, invoiceNo, cycleLabel, Date.valueOf(dueOnWorkingDay), subtotal, gst, total
        );
        for (InvoiceLineInput line : lines) {
            jdbc.update(
                "INSERT INTO fee_invoice_line (id, fee_invoice_id, fee_head_id, description, amount, discount, gst) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, line.feeHeadId(), line.description(), line.amount(), line.discount(), line.gst()
            );
        }
        return findInvoice(id).orElseThrow();
    }

    public List<FeeInvoiceLineDto> listInvoiceLines(UUID invoiceId) {
        return jdbc.query(
            "SELECT id, fee_invoice_id, fee_head_id, description, amount, discount, gst FROM fee_invoice_line WHERE fee_invoice_id = ?",
            (rs, i) -> new FeeInvoiceLineDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("fee_invoice_id")),
                UUID.fromString(rs.getString("fee_head_id")),
                rs.getString("description"),
                rs.getDouble("amount"),
                rs.getDouble("discount"),
                rs.getDouble("gst")
            ),
            invoiceId
        );
    }

    // -------------------------- Payment + Ledger --------------------------

    private static final RowMapper<PaymentDto> PAYMENT_MAPPER = (rs, i) -> new PaymentDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("fee_invoice_id")),
        rs.getDouble("amount"),
        rs.getString("gateway"),
        rs.getString("method"),
        rs.getString("status"),
        rs.getString("idempotency_key"),
        rs.getTimestamp("captured_at") == null ? null : rs.getTimestamp("captured_at").toInstant()
    );

    private static final String PAYMENT_COLS =
        "id, school_id, fee_invoice_id, amount, gateway, method, status, idempotency_key, captured_at";

    /**
     * Records a payment and posts balanced ledger entries (Bank DR / Fee
     * Receivable CR). Idempotent on {@code idempotencyKey} — a retried
     * gateway webhook returns the already-recorded payment rather than
     * double-posting.
     */
    public PaymentDto recordPayment(
        UUID schoolId, UUID feeInvoiceId, double amount, String gateway, String method, String idempotencyKey
    ) {
        academicYears.requireOpenForInvoice(feeInvoiceId);
        var existing = jdbc.query(
            "SELECT " + PAYMENT_COLS + " FROM payment WHERE idempotency_key = ?", PAYMENT_MAPPER, idempotencyKey
        );
        if (!existing.isEmpty()) return existing.get(0);

        var invoice = findInvoice(feeInvoiceId).orElseThrow(() -> new NotFoundException("Invoice not found: " + feeInvoiceId));

        UUID paymentId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO payment (id, school_id, fee_invoice_id, amount, gateway, method, status, idempotency_key, captured_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'captured', ?, now())",
            paymentId, schoolId, feeInvoiceId, amount, gateway, method, idempotencyKey
        );

        UUID journalId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO ledger_entry (id, school_id, journal_id, account_code, debit, credit, narration, source_type, source_id) " +
            "VALUES (?, ?, ?, 'BANK', ?, 0, ?, 'payment', ?)",
            UUID.randomUUID(), schoolId, journalId, amount, "Payment for " + invoice.invoiceNo(), paymentId
        );
        jdbc.update(
            "INSERT INTO ledger_entry (id, school_id, journal_id, account_code, debit, credit, narration, source_type, source_id) " +
            "VALUES (?, ?, ?, 'FEE_RECEIVABLE', 0, ?, ?, 'payment', ?)",
            UUID.randomUUID(), schoolId, journalId, amount, "Payment for " + invoice.invoiceNo(), paymentId
        );

        double newPaid = invoice.paid() + amount;
        String newStatus = newPaid >= invoice.total() ? "paid" : "partial";
        jdbc.update(
            "UPDATE fee_invoice SET paid = ?, status = ?, updated_at = now() WHERE id = ?",
            newPaid, newStatus, feeInvoiceId
        );

        return jdbc.queryForObject("SELECT " + PAYMENT_COLS + " FROM payment WHERE id = ?", PAYMENT_MAPPER, paymentId);
    }

    public List<PaymentDto> listPaymentsForInvoice(UUID invoiceId) {
        return jdbc.query(
            "SELECT " + PAYMENT_COLS + " FROM payment WHERE fee_invoice_id = ? ORDER BY created_at DESC",
            PAYMENT_MAPPER, invoiceId
        );
    }

    public List<LedgerEntryDto> ledgerForSource(String sourceType, UUID sourceId) {
        return jdbc.query(
            "SELECT id, journal_id, account_code, debit, credit, narration, source_type, source_id, posted_at " +
            "FROM ledger_entry WHERE source_type = ? AND source_id = ? ORDER BY posted_at",
            (rs, i) -> new LedgerEntryDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("journal_id")),
                rs.getString("account_code"),
                rs.getDouble("debit"),
                rs.getDouble("credit"),
                rs.getString("narration"),
                rs.getString("source_type"),
                UUID.fromString(rs.getString("source_id")),
                rs.getTimestamp("posted_at").toInstant()
            ),
            sourceType, sourceId
        );
    }
}
