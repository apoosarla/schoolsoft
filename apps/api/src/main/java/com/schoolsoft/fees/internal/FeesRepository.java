package com.schoolsoft.fees.internal;

import com.schoolsoft.fees.api.DunningPolicyDto;
import com.schoolsoft.fees.api.FeeHeadDto;
import com.schoolsoft.fees.api.FeeInvoiceDto;
import com.schoolsoft.fees.api.FeeInvoiceLineDto;
import com.schoolsoft.fees.api.FeeScheduleRunDto;
import com.schoolsoft.fees.api.LedgerEntryDto;
import com.schoolsoft.fees.api.PaymentDto;
import com.schoolsoft.fees.api.SiblingPolicyDto;
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
    private final FeeAdjustmentService adjustments;

    public FeesRepository(JdbcTemplate jdbc, AcademicYearGuard academicYears, WorkingDayService workingDays,
                          FeeAdjustmentService adjustments) {
        this.jdbc = jdbc;
        this.academicYears = academicYears;
        this.workingDays = workingDays;
        this.adjustments = adjustments;
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
        // Null on a combined family invoice: the household is the payer, not one child.
        rs.getString("student_id") == null ? null : UUID.fromString(rs.getString("student_id")),
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

    /**
     * A one-off invoice. Bulk generation from a fee structure lives in
     * {@code FeeGenerationService}; this is the manual, single-student path.
     */
    @org.springframework.transaction.annotation.Transactional
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
     * Receivable CR). Idempotent on {@code idempotencyKey} — a retried gateway
     * webhook returns the already-recorded payment rather than double-posting.
     *
     * Two parents paying at once used to lose an update: the old code read
     * {@code paid}, added, and wrote the sum back, so the second write
     * overwrote the first. The balance now moves with an atomic
     * {@code paid = paid + ?} against a locked row, and anything beyond the
     * outstanding amount is held as {@code advance_amount} rather than
     * over-crediting the bill (FEE-17).
     *
     * Transactional on purpose: the {@code FOR UPDATE} below is only a
     * serialisation point for as long as the transaction lives, and without one
     * each statement would autocommit and release the lock immediately —
     * exactly the race this is meant to close.
     */
    @org.springframework.transaction.annotation.Transactional
    public PaymentDto recordPayment(
        UUID schoolId, UUID feeInvoiceId, double amount, String gateway, String method, String idempotencyKey
    ) {
        academicYears.requireOpenForInvoice(feeInvoiceId);
        if (amount <= 0) throw new IllegalArgumentException("A payment needs a positive amount");

        var existing = jdbc.query(
            "SELECT " + PAYMENT_COLS + " FROM payment WHERE idempotency_key = ?", PAYMENT_MAPPER, idempotencyKey
        );
        if (!existing.isEmpty()) return existing.get(0);

        // Lock the invoice for the duration: the row is the serialisation point
        // between two simultaneous payers.
        var locked = jdbc.query(
            "SELECT invoice_no, total, paid FROM fee_invoice WHERE id = ? FOR UPDATE",
            (rs, i) -> new Object[]{ rs.getString("invoice_no"), rs.getDouble("total"), rs.getDouble("paid") },
            feeInvoiceId);
        if (locked.isEmpty()) throw new NotFoundException("Invoice not found: " + feeInvoiceId);
        String invoiceNo = (String) locked.get(0)[0];
        double total = (Double) locked.get(0)[1];
        double paid = (Double) locked.get(0)[2];

        double outstanding = Math.max(0, total - paid);
        double applied = Math.min(amount, outstanding);
        double advance = round(amount - applied);

        UUID paymentId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO payment (id, school_id, fee_invoice_id, amount, gateway, method, status, " +
            "  idempotency_key, captured_at) VALUES (?, ?, ?, ?, ?, ?, 'captured', ?, now())",
            paymentId, schoolId, feeInvoiceId, amount, gateway, method, idempotencyKey
        );

        UUID journalId = UUID.randomUUID();
        postLeg(schoolId, journalId, "BANK", amount, 0, "Payment for " + invoiceNo, paymentId);
        if (applied > 0) {
            postLeg(schoolId, journalId, "FEE_RECEIVABLE", 0, applied, "Payment for " + invoiceNo, paymentId);
        }
        if (advance > 0) {
            // Money the school holds but has not earned yet is a liability, not income.
            postLeg(schoolId, journalId, "ADVANCE", 0, advance, "Advance on " + invoiceNo, paymentId);
        }

        jdbc.update(
            "UPDATE fee_invoice SET paid = paid + ?, advance_amount = advance_amount + ?, updated_at = now() " +
            "WHERE id = ?",
            applied, advance, feeInvoiceId);
        adjustments.recomputeStatus(feeInvoiceId, false);

        return jdbc.queryForObject("SELECT " + PAYMENT_COLS + " FROM payment WHERE id = ?", PAYMENT_MAPPER, paymentId);
    }

    private void postLeg(UUID schoolId, UUID journalId, String account, double debit, double credit,
                         String narration, UUID paymentId) {
        jdbc.update(
            "INSERT INTO ledger_entry (id, school_id, journal_id, account_code, debit, credit, narration, " +
            "  source_type, source_id) VALUES (?, ?, ?, ?, ?, ?, ?, 'payment', ?)",
            UUID.randomUUID(), schoolId, journalId, account, debit, credit, narration, paymentId);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
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

    // -------------------------- Concessions and policies --------------------------

    public UUID grantConcession(UUID schoolId, UUID studentId, UUID academicYearId, String kind,
                                Double pct, Double flatAmount, UUID appliesToHeadId, String notes,
                                UUID approvedByStaffId) {
        if (pct == null && flatAmount == null) {
            throw new IllegalArgumentException("A concession needs either a percentage or a flat amount");
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO fee_concession (id, school_id, student_id, academic_year_id, kind, pct, " +
            "  flat_amount, applies_to_head_id, notes, approved_by_staff_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, studentId, academicYearId, kind, pct, flatAmount, appliesToHeadId, notes,
            approvedByStaffId);
        return id;
    }

    public List<java.util.Map<String, Object>> listConcessions(UUID studentId, UUID academicYearId) {
        String sql = "SELECT id, kind, pct, flat_amount, applies_to_head_id, notes FROM fee_concession " +
            "WHERE student_id = ?" + (academicYearId == null ? "" : " AND academic_year_id = ?") +
            " ORDER BY created_at";
        Object[] args = academicYearId == null ? new Object[]{ studentId }
            : new Object[]{ studentId, academicYearId };
        return jdbc.query(sql, (rs, i) -> {
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("id", rs.getString("id"));
            row.put("kind", rs.getString("kind"));
            row.put("pct", rs.getObject("pct"));
            row.put("flatAmount", rs.getObject("flat_amount"));
            row.put("appliesToHeadId", rs.getString("applies_to_head_id"));
            row.put("notes", rs.getString("notes"));
            return row;
        }, args);
    }

    public UUID upsertSiblingPolicy(UUID schoolId, UUID academicYearId, int nthChild, double pct,
                                    UUID appliesToHeadId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO sibling_concession_policy (id, school_id, academic_year_id, nth_child, pct, " +
            "  applies_to_head_id) VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (school_id, academic_year_id, nth_child) DO UPDATE SET " +
            "  pct = EXCLUDED.pct, applies_to_head_id = EXCLUDED.applies_to_head_id",
            id, schoolId, academicYearId, nthChild, pct, appliesToHeadId);
        return jdbc.queryForObject(
            "SELECT id FROM sibling_concession_policy WHERE school_id = ? AND academic_year_id = ? " +
            "  AND nth_child = ?", UUID.class, schoolId, academicYearId, nthChild);
    }

    public UUID upsertDunningPolicy(UUID schoolId, int graceDays, List<Integer> reminderDays,
                                    Double lateFeePct, Double lateFeeFlat, UUID lateFeeHeadId) {
        Integer[] days = reminderDays == null || reminderDays.isEmpty()
            ? new Integer[]{ 1, 7, 15 } : reminderDays.toArray(new Integer[0]);
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO dunning_policy (id, school_id, grace_days, reminder_days, late_fee_pct, " +
            "  late_fee_flat, late_fee_head_id) VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (school_id) DO UPDATE SET grace_days = EXCLUDED.grace_days, " +
            "  reminder_days = EXCLUDED.reminder_days, late_fee_pct = EXCLUDED.late_fee_pct, " +
            "  late_fee_flat = EXCLUDED.late_fee_flat, late_fee_head_id = EXCLUDED.late_fee_head_id, " +
            "  is_active = TRUE",
            id, schoolId, graceDays, days, lateFeePct, lateFeeFlat, lateFeeHeadId);
        return jdbc.queryForObject("SELECT id FROM dunning_policy WHERE school_id = ?", UUID.class, schoolId);
    }

    /** Empty until a school sets one; the job then leaves that school alone. */
    public Optional<DunningPolicyDto> findDunningPolicy(UUID schoolId) {
        return jdbc.query(
            "SELECT id, school_id, grace_days, reminder_days, late_fee_pct, late_fee_flat, " +
            "  late_fee_head_id, is_active FROM dunning_policy WHERE school_id = ?",
            (rs, i) -> new DunningPolicyDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("school_id")),
                rs.getInt("grace_days"),
                List.of((Integer[]) rs.getArray("reminder_days").getArray()),
                // NUMERIC arrives as BigDecimal, and both columns are nullable —
                // a school may charge a percentage, a flat fee, or neither.
                rs.getBigDecimal("late_fee_pct") == null ? null
                    : rs.getBigDecimal("late_fee_pct").doubleValue(),
                rs.getBigDecimal("late_fee_flat") == null ? null
                    : rs.getBigDecimal("late_fee_flat").doubleValue(),
                rs.getString("late_fee_head_id") == null ? null
                    : UUID.fromString(rs.getString("late_fee_head_id")),
                rs.getBoolean("is_active")),
            schoolId).stream().findFirst();
    }

    public List<SiblingPolicyDto> listSiblingPolicies(UUID schoolId, UUID academicYearId) {
        String sql = "SELECT id, school_id, academic_year_id, nth_child, pct, applies_to_head_id " +
            "FROM sibling_concession_policy WHERE school_id = ?" +
            (academicYearId == null ? "" : " AND academic_year_id = ?") + " ORDER BY nth_child";
        Object[] args = academicYearId == null ? new Object[]{ schoolId }
            : new Object[]{ schoolId, academicYearId };
        return jdbc.query(sql, (rs, i) -> new SiblingPolicyDto(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("school_id")),
            UUID.fromString(rs.getString("academic_year_id")),
            rs.getInt("nth_child"),
            rs.getDouble("pct"),
            rs.getString("applies_to_head_id") == null ? null
                : UUID.fromString(rs.getString("applies_to_head_id"))),
            args);
    }

    /**
     * The billing runs so far. Because a run row is what makes a re-run a
     * no-op, this list is also the answer to "has this cycle been billed".
     */
    public List<FeeScheduleRunDto> listScheduleRuns(UUID schoolId, UUID academicYearId) {
        String sql =
            "SELECT r.id, r.school_id, r.academic_year_id, r.cycle_label, r.grade_id, g.code AS grade_code, " +
            "       r.due_on, r.state, r.invoices_created, r.students_skipped, r.total_billed, " +
            "       r.run_by_staff_id, r.created_at " +
            "FROM fee_schedule_run r LEFT JOIN grade g ON g.id = r.grade_id " +
            "WHERE r.school_id = ?" + (academicYearId == null ? "" : " AND r.academic_year_id = ?") +
            " ORDER BY r.created_at DESC";
        Object[] args = academicYearId == null ? new Object[]{ schoolId }
            : new Object[]{ schoolId, academicYearId };
        return jdbc.query(sql, (rs, i) -> new FeeScheduleRunDto(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("school_id")),
            UUID.fromString(rs.getString("academic_year_id")),
            rs.getString("cycle_label"),
            rs.getString("grade_id") == null ? null : UUID.fromString(rs.getString("grade_id")),
            rs.getString("grade_code"),
            rs.getDate("due_on").toLocalDate(),
            rs.getString("state"),
            rs.getInt("invoices_created"),
            rs.getInt("students_skipped"),
            rs.getDouble("total_billed"),
            rs.getString("run_by_staff_id") == null ? null
                : UUID.fromString(rs.getString("run_by_staff_id")),
            rs.getTimestamp("created_at").toInstant()),
            args);
    }
}
