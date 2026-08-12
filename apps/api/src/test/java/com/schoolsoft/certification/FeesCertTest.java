package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-FEE — fees, payments & accounting. */
class FeesCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    @Disabled("fee_structure and fee_structure_line exist in the schema, but no endpoint creates or reads "
        + "them: FeesController covers heads, invoices, payments and ledger only. A school cannot define "
        + "next year's structure at all. New gap found in Phase 0 (Phase 4 owns the fee engine).")
    void cert_FEE_01_feeStructurePerGradeIsDefinedWithoutMutatingLastYears() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-09 — no bulk invoice generation from a fee structure and no fee_schedule_run, so "
        + "re-running cannot be proved idempotent (Phase 4).")
    void cert_FEE_02_bulkInvoiceGenerationIsIdempotent() {
    }

    @Test @Tag("P1")
    @Disabled("fee_concession rows have no endpoint and invoice creation never consults them, so a "
        + "concession cannot reduce an invoice or appear as a line. New gap found in Phase 0 (Phase 4).")
    void cert_FEE_03_concessionReducesTheInvoiceAsAVisibleLine() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-09 — no family grouping and no combined family invoice (Phase 4).")
    void cert_FEE_04_siblingConcessionAppliesAcrossACombinedFamilyInvoice() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-09 — no checkout initiation and no gateway callback handler; only manually recorded "
        + "payments exist (Phase 4, gateway credentials pending).")
    void cert_FEE_05_parentPaysOnlineEndToEnd() {
    }

    @Test @Tag("P1")
    void cert_FEE_06_duplicateGatewayCallbackDoesNotDoubleCredit() {
        String token = accountantToken(cbse());
        UUID invoiceId = createInvoice("DUP-" + UUID.randomUUID().toString().substring(0, 8), 5000);
        String idempotencyKey = "cert-dup-" + invoiceId;

        var first = post("/v1/fees/payments", body("schoolId", cbse().id(), "feeInvoiceId", invoiceId,
            "amount", 5000.0, "gateway", "razorpay", "method", "upi", "idempotencyKey", idempotencyKey), token);
        var second = post("/v1/fees/payments", body("schoolId", cbse().id(), "feeInvoiceId", invoiceId,
            "amount", 5000.0, "gateway", "razorpay", "method", "upi", "idempotencyKey", idempotencyKey), token);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("id").asText()).isEqualTo(first.getBody().get("id").asText());

        assertThat(count("SELECT count(*) FROM payment WHERE fee_invoice_id = ?", invoiceId)).isEqualTo(1);
        var invoice = get("/v1/fees/invoices/" + invoiceId, token).getBody();
        assertThat(invoice.get("paid").asDouble()).isEqualTo(5000.0);
        assertThat(invoice.get("status").asText()).isEqualTo("paid");

        // Balanced ledger legs, posted once.
        var ledger = get("/v1/fees/ledger?sourceType=payment&sourceId="
            + first.getBody().get("id").asText(), token).getBody();
        assertThat(ledger).hasSize(2);
        double debits = 0, credits = 0;
        for (var leg : ledger) {
            debits += leg.get("debit").asDouble();
            credits += leg.get("credit").asDouble();
        }
        assertThat(debits).isEqualTo(credits);
    }

    @Test @Tag("P1")
    void cert_FEE_07_partialPaymentLeavesTheCorrectBalance() {
        String token = accountantToken(cbse());
        UUID invoiceId = createInvoice("PART-" + UUID.randomUUID().toString().substring(0, 8), 12000);

        post("/v1/fees/payments", body("schoolId", cbse().id(), "feeInvoiceId", invoiceId,
            "amount", 4000.0, "gateway", "cash", "method", "cash",
            "idempotencyKey", "cert-part1-" + invoiceId), token);

        var afterFirst = get("/v1/fees/invoices/" + invoiceId, token).getBody();
        assertThat(afterFirst.get("status").asText()).isEqualTo("partial");
        assertThat(afterFirst.get("total").asDouble() - afterFirst.get("paid").asDouble()).isEqualTo(8000.0);

        post("/v1/fees/payments", body("schoolId", cbse().id(), "feeInvoiceId", invoiceId,
            "amount", 8000.0, "gateway", "cash", "method", "cash",
            "idempotencyKey", "cert-part2-" + invoiceId), token);

        var afterSecond = get("/v1/fees/invoices/" + invoiceId, token).getBody();
        assertThat(afterSecond.get("status").asText()).isEqualTo("paid");
        assertThat(afterSecond.get("paid").asDouble()).isEqualTo(12000.0);

        // What the parent app reads is the same record.
        UUID studentId = queryOne("SELECT student_id FROM fee_invoice WHERE id = ?", UUID.class, invoiceId);
        var parentView = get("/v1/fees/invoices?studentId=" + studentId, guardianTokenFor(cbse(), studentId)).getBody();
        assertThat(parentView).isNotEmpty();
    }

    @Test @Tag("P1")
    @Disabled("GAP-09 — no fee_adjustment: a cheque bounce cannot be reversed, only deleted (Phase 4).")
    void cert_FEE_08_chequeBounceReversesThePaymentAndRestoresDues() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-09 — nothing transitions an invoice to overdue and there is no reminder cadence; the "
        + "only scheduled job in the codebase is the outbox publisher (Phase 4).")
    void cert_FEE_09_overdueTransitionAndReminderCadenceRun() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-09 + GAP-27 — no late-fee policy, no waiver path, no audit on waiver (Phase 4).")
    void cert_FEE_10_lateFeeIsAppliedAndWaivableWithAudit() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-09 — status 'refunded' exists but nothing sets it: no refund or credit-note endpoint "
        + "and no pro-rating on withdrawal (Phase 4).")
    void cert_FEE_11_withdrawalRefundIsProRatedAndLedgerBalanced() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-09 + GAP-30 — transport fees are not derived from a route assignment, so a mid-year "
        + "route change adjusts nothing (Phase 4).")
    void cert_FEE_12_transportFeeFollowsTheRouteAssignment() {
    }

    @Test @Tag("P2")
    @Disabled("GST is stored per line but never computed: fee_head.gst_rate_pct is not applied at invoice "
        + "creation, and no IRN fields are populated. New gap found in Phase 0.")
    void cert_FEE_13_gstIsComputedOnApplicableHeadsAndIrnFailureDoesNotBlock() {
    }

    @Test @Tag("P1")
    @Disabled("No day-book or collection report: the ledger endpoint reads one source id at a time and "
        + "nothing aggregates payments over a date range. New gap found in Phase 0.")
    void cert_FEE_14_dayBookReconcilesToPaymentsAndLedger() {
    }

    @Test @Tag("P1")
    @Disabled("No outstanding-dues report by grade/section/student, and no year-end dues flag. New gap "
        + "found in Phase 0 (Phase 4 produces the arrears balance rollover consumes).")
    void cert_FEE_15_outstandingDuesReportMatchesInvoiceBalances() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-02 + GAP-09 — no rollover, so arrears cannot carry into next year's opening balance "
        + "(Phase 6).")
    void cert_FEE_16_arrearsCarryForwardIntoTheNextYearsOpeningBalance() {
    }

    @Test @Tag("P2")
    @Disabled("recordPayment reads invoice.paid and writes back paid + amount with no guard: two "
        + "simultaneous payments lose an update and can over-credit, and there is no advance-payment "
        + "path. New gap found in Phase 0.")
    void cert_FEE_17_simultaneousPaymentsDoNotOverCredit() {
    }

    // ---------------------------------------------------------------- helpers

    private UUID createInvoice(String invoiceNo, double amount) {
        UUID studentId = firstStudentIn(currentFocusSection(cbse()));
        UUID headId = queryOne("SELECT id FROM fee_head WHERE school_id = ? AND code = 'TUITION'",
            UUID.class, cbse().id());
        var created = post("/v1/fees/invoices", body(
            "schoolId", cbse().id(), "studentId", studentId, "invoiceNo", invoiceNo,
            "cycleLabel", "Certification cycle", "dueOn", "2026-09-10",
            "lines", List.of(Map.of("feeHeadId", headId, "description", "Tuition", "amount", amount,
                "discount", 0, "gst", 0))), accountantToken(cbse()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }
}
