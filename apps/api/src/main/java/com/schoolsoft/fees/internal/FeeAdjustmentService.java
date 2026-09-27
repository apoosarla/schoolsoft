package com.schoolsoft.fees.internal;

import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.fees.api.FeeAdjustmentDto;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything that changes a bill after it was issued (FEE-08/10/11, LIB-03/04).
 *
 * Each kind moves money in a specific direction, and each posts a balanced
 * pair of ledger legs — a school's accounts and a parent's outstanding balance
 * are two views of the same rows, so neither can be corrected without the
 * other following:
 *
 * <pre>
 *   charge / late_fee  raises the amount owed   FEE_RECEIVABLE DR / income CR
 *   credit_note        lowers the amount owed   income DR / FEE_RECEIVABLE CR
 *   waiver             lowers the amount owed   FEE_WAIVER DR / FEE_RECEIVABLE CR
 *   reversal           un-does a payment        FEE_RECEIVABLE (+ ADVANCE) DR / BANK CR
 *   refund             pays money back out      FEE_RECEIVABLE (+ ADVANCE) DR / REFUND CR
 * </pre>
 *
 * A bounced cheque is a {@code reversal}, never a deleted payment: the school
 * has to be able to show that the money arrived and went away again.
 */
@Service
public class FeeAdjustmentService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public FeeAdjustmentService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static final RowMapper<FeeAdjustmentDto> MAPPER = (rs, i) -> new FeeAdjustmentDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("fee_invoice_id")),
        rs.getString("payment_id") == null ? null : UUID.fromString(rs.getString("payment_id")),
        rs.getString("kind"),
        rs.getDouble("amount"),
        rs.getString("reason"),
        rs.getString("approved_by_staff_id") == null ? null
            : UUID.fromString(rs.getString("approved_by_staff_id")),
        rs.getTimestamp("created_at").toInstant()
    );

    private static final String COLS =
        "id, school_id, fee_invoice_id, payment_id, kind, amount, reason, approved_by_staff_id, created_at";

    public List<FeeAdjustmentDto> listForInvoice(UUID invoiceId) {
        return jdbc.query("SELECT " + COLS + " FROM fee_adjustment WHERE fee_invoice_id = ? " +
            "ORDER BY created_at", MAPPER, invoiceId);
    }

    @Transactional
    public FeeAdjustmentDto adjust(UUID schoolId, UUID invoiceId, String kind, double amount, String reason,
                                   UUID paymentId, UUID approvedByStaffId, UUID feeHeadId) {
        if (amount <= 0) throw new IllegalArgumentException("An adjustment needs a positive amount");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An adjustment needs a reason — it is somebody's decision");
        }
        // Locked: a reversal reads what is left of a payment and then writes
        // it down, and two of them at once must not both see the same balance.
        var invoice = jdbc.query(
            "SELECT invoice_no, total, paid, status, advance_amount FROM fee_invoice WHERE id = ? FOR UPDATE",
            (rs, i) -> new Object[]{ rs.getString("invoice_no"), rs.getDouble("total"),
                                     rs.getDouble("paid"), rs.getString("status"),
                                     rs.getDouble("advance_amount") },
            invoiceId);
        if (invoice.isEmpty()) throw new NotFoundException("Invoice not found: " + invoiceId);
        String invoiceNo = (String) invoice.get(0)[0];
        double advanceHeld = (Double) invoice.get(0)[4];

        PaymentUnwind unwind = null;
        if ("reversal".equals(kind) || "refund".equals(kind)) {
            unwind = planUnwind(invoiceId, invoiceNo, kind, amount, paymentId, advanceHeld);
            double paid = (Double) invoice.get(0)[2];
            if (unwind.receivable() > paid + 0.005) {
                throw new IllegalArgumentException(
                    "Cannot take back " + amount + " on " + invoiceNo + ": only " + paid
                        + " of it went to the bill");
            }
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO fee_adjustment (id, school_id, fee_invoice_id, payment_id, kind, amount, reason, " +
            "  approved_by_staff_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, invoiceId, paymentId, kind, amount, reason, approvedByStaffId);

        switch (kind) {
            case "charge", "late_fee" -> {
                jdbc.update("UPDATE fee_invoice SET total = total + ?, updated_at = now() WHERE id = ?",
                    amount, invoiceId);
                addLine(invoiceId, feeHeadId, schoolId, reason, amount);
                post(schoolId, id, "FEE_RECEIVABLE", amount, 0, reason, invoiceNo);
                post(schoolId, id, "late_fee".equals(kind) ? "LATE_FEE" : "FEE_INCOME", 0, amount,
                    reason, invoiceNo);
            }
            case "credit_note" -> {
                jdbc.update("UPDATE fee_invoice SET total = GREATEST(total - ?, paid), updated_at = now() " +
                    "WHERE id = ?", amount, invoiceId);
                addLine(invoiceId, feeHeadId, schoolId, reason, -amount);
                post(schoolId, id, "FEE_INCOME", amount, 0, reason, invoiceNo);
                post(schoolId, id, "FEE_RECEIVABLE", 0, amount, reason, invoiceNo);
            }
            case "waiver" -> {
                jdbc.update("UPDATE fee_invoice SET total = GREATEST(total - ?, paid), updated_at = now() " +
                    "WHERE id = ?", amount, invoiceId);
                addLine(invoiceId, feeHeadId, schoolId, reason, -amount);
                post(schoolId, id, "FEE_WAIVER", amount, 0, reason, invoiceNo);
                post(schoolId, id, "FEE_RECEIVABLE", 0, amount, reason, invoiceNo);
            }
            case "reversal", "refund" -> {
                // The advance comes back first: it is the part of the payment
                // the invoice never used, so a bounced overpayment restores
                // exactly the dues it cleared and no more.
                jdbc.update("UPDATE fee_invoice SET paid = paid - ?, advance_amount = advance_amount - ?, " +
                    "updated_at = now() WHERE id = ?", unwind.receivable(), unwind.advance(), invoiceId);
                if (unwind.receivable() > 0) {
                    post(schoolId, id, "FEE_RECEIVABLE", unwind.receivable(), 0, reason, invoiceNo);
                }
                if (unwind.advance() > 0) post(schoolId, id, "ADVANCE", unwind.advance(), 0, reason, invoiceNo);
                post(schoolId, id, "refund".equals(kind) ? "REFUND" : "BANK", 0, amount, reason, invoiceNo);
                // Only a payment that has gone back in full changes state; a
                // part-refund leaves the rest of it captured.
                if (unwind.whole()) {
                    jdbc.update("UPDATE payment SET status = ? WHERE id = ? AND status = 'captured'",
                        "refund".equals(kind) ? "refunded" : "failed", paymentId);
                }
            }
            default -> throw new IllegalArgumentException("Unknown adjustment kind: " + kind);
        }

        recomputeStatus(invoiceId, "refund".equals(kind));

        // Waivers and refunds are exactly the mutations an auditor asks about.
        audit.record("fee.adjustment." + kind, "fee_invoice", invoiceId, null,
            Map.of("amount", amount, "reason", reason, "adjustmentId", id.toString()));
        return jdbc.queryForObject("SELECT " + COLS + " FROM fee_adjustment WHERE id = ?", MAPPER, id);
    }

    /** How much of a reversal comes out of the advance and how much out of the dues it paid. */
    private record PaymentUnwind(double advance, double receivable, boolean whole) {}

    /**
     * A reversal or refund names the payment it un-does and is bounded by what
     * is left of that payment. Before, it named nothing and was bounded by the
     * invoice's {@code paid} — which an overpayment never raises past the
     * total — so a bounced overpaid cheque could not be reversed at all, and
     * one payment could be reversed as many times as the invoice had room for.
     */
    private PaymentUnwind planUnwind(UUID invoiceId, String invoiceNo, String kind, double amount,
                                     UUID paymentId, double advanceHeld) {
        if (paymentId == null) {
            throw new IllegalArgumentException("A " + kind + " must name the payment it un-does");
        }
        var payment = jdbc.query(
            "SELECT amount, status FROM payment WHERE id = ? AND fee_invoice_id = ?",
            (rs, i) -> new Object[]{ rs.getDouble("amount"), rs.getString("status") },
            paymentId, invoiceId);
        if (payment.isEmpty()) {
            throw new NotFoundException("No payment " + paymentId + " on " + invoiceNo);
        }
        if (!"captured".equals(payment.get(0)[1])) {
            throw new ConflictException("That payment is already " + payment.get(0)[1]);
        }
        double already = jdbc.queryForObject(
            "SELECT COALESCE(sum(amount), 0) FROM fee_adjustment " +
            "WHERE payment_id = ? AND kind IN ('reversal', 'refund')", Double.class, paymentId);
        double remaining = round((Double) payment.get(0)[0] - already);
        if (amount > remaining + 0.005) {
            throw new IllegalArgumentException(
                "Only " + remaining + " of that payment is left to " + kind + " on " + invoiceNo);
        }
        // The payment's own advance leg, less what earlier reversals of it took back.
        double paymentAdvance = jdbc.queryForObject(
            "SELECT COALESCE(sum(credit - debit), 0) FROM ledger_entry " +
            "WHERE account_code = 'ADVANCE' AND source_type = 'payment' AND source_id = ?",
            Double.class, paymentId);
        double advanceReturned = jdbc.queryForObject(
            "SELECT COALESCE(sum(l.debit), 0) FROM ledger_entry l JOIN fee_adjustment a ON a.id = l.source_id " +
            "WHERE l.account_code = 'ADVANCE' AND l.source_type = 'adjustment' AND a.payment_id = ?",
            Double.class, paymentId);
        double advance = round(Math.min(amount, Math.min(advanceHeld, Math.max(0, paymentAdvance - advanceReturned))));
        return new PaymentUnwind(advance, round(amount - advance), amount + 0.005 >= remaining);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void addLine(UUID invoiceId, UUID feeHeadId, UUID schoolId, String description, double amount) {
        UUID headId = feeHeadId;
        if (headId == null) {
            headId = jdbc.queryForObject(
                "SELECT id FROM fee_head WHERE school_id = ? ORDER BY (code = 'TUITION') DESC, code LIMIT 1",
                UUID.class, schoolId);
        }
        if (headId == null) return;                  // school has no heads configured yet
        jdbc.update(
            "INSERT INTO fee_invoice_line (id, fee_invoice_id, fee_head_id, description, amount, discount, " +
            "  gst, source) VALUES (?, ?, ?, ?, ?, 0, 0, 'adjustment')",
            UUID.randomUUID(), invoiceId, headId, description, amount);
    }

    private void post(UUID schoolId, UUID adjustmentId, String account, double debit, double credit,
                      String reason, String invoiceNo) {
        jdbc.update(
            "INSERT INTO ledger_entry (id, school_id, journal_id, account_code, debit, credit, narration, " +
            "  source_type, source_id) VALUES (?, ?, ?, ?, ?, ?, ?, 'adjustment', ?)",
            UUID.randomUUID(), schoolId, journalFor(adjustmentId), account, debit, credit,
            reason + " (" + invoiceNo + ")", adjustmentId);
    }

    /** One journal per adjustment, so its legs group together in the ledger. */
    private UUID journalFor(UUID adjustmentId) {
        return adjustmentId;
    }

    /**
     * Status is derived, never assigned: it is a function of what is owed, what
     * has been paid, and whether the due date has passed. Two code paths
     * setting it by hand is how an invoice ends up "paid" with a balance.
     */
    public void recomputeStatus(UUID invoiceId, boolean refunded) {
        var invoice = jdbc.query(
            "SELECT total, paid, due_on, status FROM fee_invoice WHERE id = ?",
            (rs, i) -> new Object[]{ rs.getDouble("total"), rs.getDouble("paid"),
                                     rs.getDate("due_on").toLocalDate(), rs.getString("status") },
            invoiceId);
        if (invoice.isEmpty()) return;
        double total = (Double) invoice.get(0)[0];
        double paid = (Double) invoice.get(0)[1];
        LocalDate dueOn = (LocalDate) invoice.get(0)[2];
        String current = (String) invoice.get(0)[3];
        if ("cancelled".equals(current)) return;

        String status;
        if (refunded && paid <= 0.005) {
            status = "refunded";
        } else if (paid + 0.005 >= total) {
            status = "paid";
        } else if (paid > 0.005) {
            status = "partial";
        } else if (dueOn.isBefore(LocalDate.now())) {
            status = "overdue";
        } else {
            status = "open";
        }
        jdbc.update("UPDATE fee_invoice SET status = ?, updated_at = now() WHERE id = ?", status, invoiceId);
    }
}
