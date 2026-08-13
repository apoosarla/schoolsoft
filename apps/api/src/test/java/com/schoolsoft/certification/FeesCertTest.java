package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/** CERT-FEE — fees, payments & accounting. */
class FeesCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_FEE_01_feeStructurePerGradeIsDefinedWithoutMutatingLastYears() {
        String token = accountantToken(cie());
        UUID gradeId = gradeOf(cie(), cie().focusGradeCode());
        UUID tuition = headOf(cie(), "TUITION");
        UUID lab = headOf(cie(), "LAB");

        var created = post("/v1/fees/structures", body(
            "schoolId", cie().id(), "gradeId", gradeId, "academicYearId", cie().currentAy().id(),
            "name", "CERT-FEE01 structure",
            "schedule", Map.of("cadence", "quarterly", "instalments", 4),
            "lines", List.of(Map.of("feeHeadId", tuition, "amount", 40000.0),
                             Map.of("feeHeadId", lab, "amount", 5000.0))), token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID thisYear = UUID.fromString(created.getBody().get("id").asText());
        assertThat(created.getBody().get("total").asDouble()).isEqualTo(45000.0);

        // Next year is a copy, then edited. The copy must not reach back.
        UUID nextAy = UUID.fromString(post("/v1/tenancy/schools/" + cie().id() + "/academic-years",
            Map.of("code", "2027-28-FEE01", "startsOn", "2027-04-01", "endsOn", "2028-03-31",
                "isCurrent", false), principalToken(cie())).getBody().get("id").asText());
        try {
            var clone = post("/v1/fees/structures/" + thisYear + "/clone",
                Map.of("targetAcademicYearId", nextAy, "name", "CERT-FEE01 structure 2027-28"), token);
            assertThat(clone.getStatusCode()).isEqualTo(HttpStatus.OK);
            UUID nextYear = UUID.fromString(clone.getBody().get("id").asText());
            assertThat(clone.getBody().get("total").asDouble()).isEqualTo(45000.0);

            put("/v1/fees/structures/" + nextYear + "/lines", Map.of(
                "lines", List.of(Map.of("feeHeadId", tuition, "amount", 46000.0),
                                 Map.of("feeHeadId", lab, "amount", 5500.0))), token);

            assertThat(get("/v1/fees/structures/" + nextYear, token).getBody().get("total").asDouble())
                .isEqualTo(51500.0);
            assertThat(get("/v1/fees/structures/" + thisYear, token).getBody().get("total").asDouble())
                .isEqualTo(45000.0);
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM fee_structure_line WHERE fee_structure_id IN " +
                    "(SELECT id FROM fee_structure WHERE name LIKE 'CERT-FEE01%')");
                jdbc.update("DELETE FROM fee_structure WHERE name LIKE 'CERT-FEE01%'");
                jdbc.update("DELETE FROM academic_year WHERE id = ?", nextAy);
            });
        }
    }

    @Test @Tag("P1")
    void cert_FEE_02_bulkInvoiceGenerationIsIdempotent() {
        String token = accountantToken(cie());
        UUID gradeId = gradeOf(cie(), cie().terminalGradeCode());
        String cycle = "CERT-FEE02 cycle";

        var first = generate(cie(), gradeId, cycle, "2026-09-10", token);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        int createdCount = first.getBody().get("invoicesCreated").asInt();
        assertThat(createdCount).isGreaterThan(0);
        assertThat(first.getBody().get("alreadyRun").asBoolean()).isFalse();

        try {
            long invoiced = count("SELECT count(*) FROM fee_invoice WHERE cycle_label = ?", cycle);
            assertThat(invoiced).isEqualTo(createdCount);

            // Re-running the same cycle bills nobody twice.
            var second = generate(cie(), gradeId, cycle, "2026-09-10", token);
            assertThat(second.getBody().get("alreadyRun").asBoolean()).isTrue();
            assertThat(count("SELECT count(*) FROM fee_invoice WHERE cycle_label = ?", cycle))
                .isEqualTo(invoiced);

            // The run is listed, which is how an operator answers "has this cycle
            // been billed" without reading invoices.
            var runs = get("/v1/fees/runs?schoolId=" + cie().id()
                + "&academicYearId=" + cie().currentAy().id(), token).getBody();
            boolean listed = false;
            for (var row : runs) {
                if (cycle.equals(row.get("cycleLabel").asText())) {
                    assertThat(row.get("invoicesCreated").asInt()).isEqualTo(createdCount);
                    assertThat(row.get("state").asText()).isEqualTo("completed");
                    listed = true;
                }
            }
            assertThat(listed).isTrue();

            // And the database refuses it too, so a concurrent second run cannot
            // slip past the run record.
            UUID studentId = queryOne("SELECT student_id FROM fee_invoice WHERE cycle_label = ? LIMIT 1",
                UUID.class, cycle);
            UUID runId = queryOne("SELECT fee_schedule_run_id FROM fee_invoice WHERE cycle_label = ? LIMIT 1",
                UUID.class, cycle);
            assertThatThrownBy(() -> inChainDo(jdbc -> jdbc.update(
                "INSERT INTO fee_invoice (id, school_id, student_id, invoice_no, cycle_label, due_on, " +
                "  subtotal, gst, total, fee_schedule_run_id) " +
                "VALUES (gen_random_uuid(), ?, ?, 'DUPE-FEE02', ?, '2026-09-10', 1, 0, 1, ?)",
                cie().id(), studentId, cycle, runId)))
                .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            clearCycle(cycle);
        }
    }

    @Test @Tag("P1")
    void cert_FEE_03_concessionReducesTheInvoiceAsAVisibleLine() {
        String token = accountantToken(cie());
        UUID gradeId = gradeOf(cie(), cie().terminalGradeCode());
        UUID sectionId = sectionOf(cie(), cie().currentAy().code(), cie().terminalGradeCode(), "A");
        UUID scholar = firstStudentIn(sectionId);
        String cycle = "CERT-FEE03 cycle";

        var granted = post("/v1/fees/concessions", body(
            "schoolId", cie().id(), "studentId", scholar, "academicYearId", cie().currentAy().id(),
            "kind", "scholarship", "pct", 25.0, "appliesToHeadId", headOf(cie(), "TUITION"),
            "notes", "Merit scholarship", "approvedByStaffId", cie().principalStaffId()), token);
        assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.OK);

        try {
            generate(cie(), gradeId, cycle, "2026-09-10", token);

            UUID invoiceId = queryOne(
                "SELECT id FROM fee_invoice WHERE student_id = ? AND cycle_label = ?",
                UUID.class, scholar, cycle);
            var lines = get("/v1/fees/invoices/" + invoiceId + "/lines", token).getBody();

            // The discount is a visible line, not a smaller number.
            double discount = 0;
            for (var line : lines) discount += line.get("discount").asDouble();
            assertThat(discount).isGreaterThan(0);

            double tuitionAmount = queryOne(
                "SELECT l.amount FROM fee_structure_line l JOIN fee_structure s ON s.id = l.fee_structure_id " +
                "WHERE s.grade_id = ? AND s.academic_year_id = ? AND l.fee_head_id = ?",
                Double.class, gradeId, cie().currentAy().id(), headOf(cie(), "TUITION"));
            assertThat(discount).isEqualTo(tuitionAmount * 0.25);

            // A classmate without the concession pays the full amount.
            UUID classmate = studentsIn(sectionId).get(1);
            double theirTotal = queryOne("SELECT total FROM fee_invoice WHERE student_id = ? AND cycle_label = ?",
                Double.class, classmate, cycle);
            double scholarTotal = queryOne("SELECT total FROM fee_invoice WHERE id = ?", Double.class, invoiceId);
            assertThat(scholarTotal).isEqualTo(theirTotal - discount);
        } finally {
            clearCycle(cycle);
            inChainDo(jdbc -> jdbc.update("DELETE FROM fee_concession WHERE student_id = ?", scholar));
        }
    }

    @Test @Tag("P2")
    void cert_FEE_04_siblingConcessionAppliesAcrossACombinedFamilyInvoice() {
        String token = accountantToken(cbse());
        var siblings = siblingPair(cbse());
        String cycle = "CERT-FEE04 cycle";

        post("/v1/fees/sibling-policies", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
            "nthChild", 2, "pct", 20.0), token);
        var linked = post("/v1/fees/families/link",
            body("schoolId", cbse().id(), "studentId", siblings[0]), token);
        assertThat(linked.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID familyId = UUID.fromString(linked.getBody().get("familyId").asText());

        try {
            // Both children are in the same household.
            assertThat(queryOne("SELECT family_id FROM student WHERE id = ?", UUID.class, siblings[1]))
                .isEqualTo(familyId);

            var policies = get("/v1/fees/sibling-policies?schoolId=" + cbse().id()
                + "&academicYearId=" + cbse().currentAy().id(), token).getBody();
            assertThat(policies.get(0).get("nthChild").asInt()).isEqualTo(2);
            assertThat(policies.get(0).get("pct").asDouble()).isEqualTo(20.0);

            generate(cbse(), gradeOf(cbse(), gradeCodeOf(siblings[0])), cycle, "2026-09-10", token);
            generate(cbse(), gradeOf(cbse(), gradeCodeOf(siblings[1])), cycle, "2026-09-10", token);

            double elder = queryOne("SELECT total FROM fee_invoice WHERE student_id = ? AND cycle_label = ?",
                Double.class, siblings[0], cycle);
            double younger = queryOne("SELECT total FROM fee_invoice WHERE student_id = ? AND cycle_label = ?",
                Double.class, siblings[1], cycle);
            assertThat(younger).isLessThan(elder);

            // One bill for the household, carrying both children's balances.
            var combined = post("/v1/fees/families/combined-invoice", body(
                "schoolId", cbse().id(), "familyId", familyId, "cycleLabel", "CERT-FEE04 family",
                "dueOn", "2026-09-20"), token);
            assertThat(combined.getStatusCode()).isEqualTo(HttpStatus.OK);
            UUID combinedId = UUID.fromString(combined.getBody().get("id").asText());
            var combinedLines = get("/v1/fees/invoices/" + combinedId + "/lines", token).getBody();
            assertThat(combinedLines.size()).isGreaterThanOrEqualTo(2);

            // And the guardian sees both children under one login (ADM-11).
            UUID guardianId = queryOne(
                "SELECT gs.guardian_id FROM guardian_student gs WHERE gs.student_id IN (?, ?) " +
                "GROUP BY gs.guardian_id HAVING count(DISTINCT gs.student_id) = 2 LIMIT 1",
                UUID.class, siblings[0], siblings[1]);
            var children = get("/v1/people/guardians/" + guardianId + "/students",
                guardianTokenFor(cbse(), siblings[0])).getBody();
            assertThat(children.size()).isGreaterThanOrEqualTo(2);
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM fee_invoice_line WHERE fee_invoice_id IN " +
                    "(SELECT id FROM fee_invoice WHERE cycle_label LIKE 'CERT-FEE04%')");
                jdbc.update("DELETE FROM fee_invoice WHERE cycle_label LIKE 'CERT-FEE04%'");
                jdbc.update("DELETE FROM fee_schedule_run WHERE cycle_label LIKE 'CERT-FEE04%'");
                jdbc.update("DELETE FROM sibling_concession_policy WHERE school_id = ?", cbse().id());
                jdbc.update("UPDATE student SET family_id = NULL WHERE family_id = ?", familyId);
                jdbc.update("DELETE FROM family WHERE id = ?", familyId);
            });
        }
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
    void cert_FEE_08_chequeBounceReversesThePaymentAndRestoresDues() {
        String token = accountantToken(cbse());
        UUID invoiceId = createInvoice("BOUNCE-" + UUID.randomUUID().toString().substring(0, 8), 9000);

        UUID paymentId = UUID.fromString(post("/v1/fees/payments", body(
            "schoolId", cbse().id(), "feeInvoiceId", invoiceId, "amount", 9000.0,
            "gateway", "cheque", "method", "cheque", "idempotencyKey", "cert-bounce-" + invoiceId), token)
            .getBody().get("id").asText());
        assertThat(get("/v1/fees/invoices/" + invoiceId, token).getBody().get("status").asText())
            .isEqualTo("paid");

        var reversed = post("/v1/fees/invoices/" + invoiceId + "/adjustments", body(
            "schoolId", cbse().id(), "kind", "reversal", "amount", 9000.0,
            "reason", "Cheque 004521 returned unpaid", "paymentId", paymentId,
            "approvedByStaffId", cbse().accountantStaffId()), token);
        assertThat(reversed.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The dues come back, and the payment is retained as failed rather than
        // deleted — the school has to be able to show the cheque existed.
        var invoice = get("/v1/fees/invoices/" + invoiceId, token).getBody();
        assertThat(invoice.get("paid").asDouble()).isEqualTo(0.0);
        assertThat(invoice.get("status").asText()).isIn("open", "overdue");
        assertThat(queryOne("SELECT status FROM payment WHERE id = ?", String.class, paymentId))
            .isEqualTo("failed");

        // Balanced legs for the reversal.
        assertThat(journalBalances("adjustment",
            queryOne("SELECT id FROM fee_adjustment WHERE fee_invoice_id = ? AND kind = 'reversal'",
                UUID.class, invoiceId))).isTrue();
    }

    @Test @Tag("P1")
    void cert_FEE_09_overdueTransitionAndReminderCadenceRun() {
        String token = accountantToken(cbse());
        UUID studentId = studentsIn(currentFocusSection(cbse())).get(2);
        UUID invoiceId = createInvoiceFor(studentId, "DUN-" + UUID.randomUUID().toString().substring(0, 8),
            6000, "2026-08-01");

        put("/v1/fees/dunning-policy", body(
            "schoolId", cbse().id(), "graceDays", 3, "reminderDays", List.of(1, 7)), token);
        long dispatchesBefore = count("SELECT count(*) FROM notification_dispatch WHERE school_id = ?",
            cbse().id());

        try {
            // The cadence reads back, so an operator can see what a family will be
            // sent before changing it.
            var policy = get("/v1/fees/dunning-policy?schoolId=" + cbse().id(), token);
            assertThat(policy.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(policy.getBody().get("graceDays").asInt()).isEqualTo(3);
            assertThat(policy.getBody().get("reminderDays").toString()).isEqualTo("[1,7]");
            assertThat(policy.getBody().hasNonNull("lateFeePct")).isFalse();   // no late fee configured

            var run = post("/v1/fees/dunning/run",
                body("schoolId", cbse().id(), "asOf", "2026-08-12"), token);
            assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(run.getBody().get("markedOverdue").asInt()).isGreaterThan(0);
            assertThat(run.getBody().get("remindersSent").asInt()).isGreaterThan(0);

            assertThat(get("/v1/fees/invoices/" + invoiceId, token).getBody().get("status").asText())
                .isEqualTo("overdue");
            assertThat(count("SELECT count(*) FROM notification_dispatch WHERE school_id = ?", cbse().id()))
                .isGreaterThan(dispatchesBefore);

            long remindersAfterFirst = count(
                "SELECT count(*) FROM dunning_event WHERE fee_invoice_id = ? AND kind = 'reminder'", invoiceId);
            assertThat(remindersAfterFirst).isEqualTo(2);       // the 1-day and 7-day rungs

            // Running again the same day sends nothing further: a family gets one
            // reminder per rung, however often the job runs.
            var repeat = post("/v1/fees/dunning/run",
                body("schoolId", cbse().id(), "asOf", "2026-08-12"), token);
            assertThat(repeat.getBody().get("remindersSent").asInt()).isZero();
            assertThat(count("SELECT count(*) FROM dunning_event WHERE fee_invoice_id = ? AND kind = 'reminder'",
                invoiceId)).isEqualTo(remindersAfterFirst);
        } finally {
            clearDunning(invoiceId);
        }
    }

    @Test @Tag("P2")
    void cert_FEE_10_lateFeeIsAppliedAndWaivableWithAudit() {
        String token = accountantToken(cbse());
        UUID studentId = studentsIn(currentFocusSection(cbse())).get(3);
        UUID invoiceId = createInvoiceFor(studentId, "LATE-" + UUID.randomUUID().toString().substring(0, 8),
            10000, "2026-08-01");

        put("/v1/fees/dunning-policy", body(
            "schoolId", cbse().id(), "graceDays", 5, "reminderDays", List.of(7),
            "lateFeePct", 2.0, "lateFeeHeadId", headOf(cbse(), "TUITION")), token);

        try {
            post("/v1/fees/dunning/run", body("schoolId", cbse().id(), "asOf", "2026-08-12"), token);

            var withLateFee = get("/v1/fees/invoices/" + invoiceId, token).getBody();
            assertThat(withLateFee.get("total").asDouble()).isEqualTo(10200.0);   // 2% of 10,000
            UUID lateFeeId = queryOne(
                "SELECT id FROM fee_adjustment WHERE fee_invoice_id = ? AND kind = 'late_fee'",
                UUID.class, invoiceId);
            assertThat(journalBalances("adjustment", lateFeeId)).isTrue();

            // Waiving it needs a reason, and leaves an audit trail.
            var unreasoned = post("/v1/fees/invoices/" + invoiceId + "/adjustments", body(
                "schoolId", cbse().id(), "kind", "waiver", "amount", 200.0, "reason", ""), token);
            assertThat(unreasoned.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            var waived = post("/v1/fees/invoices/" + invoiceId + "/adjustments", body(
                "schoolId", cbse().id(), "kind", "waiver", "amount", 200.0,
                "reason", "Bank transfer was delayed at the bank's end",
                "approvedByStaffId", cbse().principalStaffId()), token);
            assertThat(waived.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get("/v1/fees/invoices/" + invoiceId, token).getBody().get("total").asDouble())
                .isEqualTo(10000.0);
            assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'fee.adjustment.waiver' " +
                "AND target_id = ?", invoiceId)).isGreaterThan(0);
        } finally {
            clearDunning(invoiceId);
        }
    }

    @Test @Tag("P1")
    void cert_FEE_11_withdrawalRefundIsProRatedAndLedgerBalanced() {
        String token = accountantToken(cbse());
        UUID invoiceId = createInvoice("REF-" + UUID.randomUUID().toString().substring(0, 8), 12000);

        UUID paymentId = UUID.fromString(post("/v1/fees/payments", body(
            "schoolId", cbse().id(), "feeInvoiceId", invoiceId, "amount", 12000.0,
            "gateway", "cash", "method", "cash", "idempotencyKey", "cert-refund-" + invoiceId), token)
            .getBody().get("id").asText());

        // Withdrawn halfway through the term: half the fee is credited, the rest
        // refunded, and the invoice ends up owing nothing.
        var creditNote = post("/v1/fees/invoices/" + invoiceId + "/adjustments", body(
            "schoolId", cbse().id(), "kind", "credit_note", "amount", 6000.0,
            "reason", "Withdrawal from 15 Sep — unused half of the term",
            "approvedByStaffId", cbse().principalStaffId()), token);
        assertThat(creditNote.getStatusCode()).isEqualTo(HttpStatus.OK);

        var refund = post("/v1/fees/invoices/" + invoiceId + "/adjustments", body(
            "schoolId", cbse().id(), "kind", "refund", "amount", 12000.0,
            "reason", "Refund on withdrawal", "paymentId", paymentId,
            "approvedByStaffId", cbse().principalStaffId()), token);
        assertThat(refund.getStatusCode()).isEqualTo(HttpStatus.OK);

        var invoice = get("/v1/fees/invoices/" + invoiceId, token).getBody();
        assertThat(invoice.get("paid").asDouble()).isEqualTo(0.0);
        assertThat(invoice.get("status").asText()).isEqualTo("refunded");

        for (String kind : List.of("credit_note", "refund")) {
            assertThat(journalBalances("adjustment", queryOne(
                "SELECT id FROM fee_adjustment WHERE fee_invoice_id = ? AND kind = ?",
                UUID.class, invoiceId, kind))).isTrue();
        }
    }

    @Test @Tag("P2")
    void cert_FEE_12_transportFeeFollowsTheRouteAssignment() {
        String token = accountantToken(cie());
        UUID gradeId = gradeOf(cie(), cie().terminalGradeCode());
        UUID sectionId = sectionOf(cie(), cie().currentAy().code(), cie().terminalGradeCode(), "A");
        UUID rider = firstStudentIn(sectionId);
        UUID walker = studentsIn(sectionId).get(1);

        UUID routeId = queryOne("SELECT id FROM transport_route WHERE school_id = ? LIMIT 1",
            UUID.class, cie().id());
        UUID stopId = queryOne("SELECT id FROM transport_stop WHERE route_id = ? ORDER BY sort_order LIMIT 1",
            UUID.class, routeId);
        UUID transportHead = headOf(cie(), "TRANSPORT");
        inChainDo(jdbc -> jdbc.update(
            "UPDATE transport_route SET monthly_fee = 2000, fee_head_id = ? WHERE id = ?",
            transportHead, routeId));
        inChainDo(jdbc -> jdbc.update(
            "INSERT INTO student_transport (id, school_id, student_id, route_id, stop_id, starts_on) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, '2026-04-01')",
            cie().id(), rider, routeId, stopId));

        try {
            generate(cie(), gradeId, "CERT-FEE12 September", "2026-09-10", token);

            assertThat(lineSources(rider, "CERT-FEE12 September")).contains("transport");
            assertThat(lineSources(walker, "CERT-FEE12 September")).doesNotContain("transport");

            // The child leaves the route at the end of September; October's bill
            // no longer carries it (TRN-06).
            inChainDo(jdbc -> jdbc.update(
                "UPDATE student_transport SET ends_on = '2026-09-30' WHERE student_id = ?", rider));
            generate(cie(), gradeId, "CERT-FEE12 October", "2026-10-10", token);
            assertThat(lineSources(rider, "CERT-FEE12 October")).doesNotContain("transport");
        } finally {
            clearCycle("CERT-FEE12 September");
            clearCycle("CERT-FEE12 October");
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM student_transport WHERE student_id = ?", rider);
                jdbc.update("UPDATE transport_route SET monthly_fee = NULL, fee_head_id = NULL WHERE id = ?",
                    routeId);
            });
        }
    }

    @Test @Tag("P2")
    void cert_FEE_13_gstIsComputedOnApplicableHeadsAndIrnFailureDoesNotBlock() {
        String token = accountantToken(cie());
        UUID gradeId = gradeOf(cie(), cie().terminalGradeCode());
        UUID labHead = headOf(cie(), "LAB");
        String cycle = "CERT-FEE13 cycle";

        // Tuition is exempt; the lab head attracts 18%.
        inChainDo(jdbc -> jdbc.update("UPDATE fee_head SET gst_rate_pct = 18 WHERE id = ?", labHead));
        try {
            generate(cie(), gradeId, cycle, "2026-09-10", token);

            UUID invoiceId = queryOne("SELECT id FROM fee_invoice WHERE cycle_label = ? LIMIT 1",
                UUID.class, cycle);
            var invoice = get("/v1/fees/invoices/" + invoiceId, token).getBody();
            double labAmount = queryOne(
                "SELECT l.amount FROM fee_structure_line l JOIN fee_structure s ON s.id = l.fee_structure_id " +
                "WHERE s.grade_id = ? AND s.academic_year_id = ? AND l.fee_head_id = ?",
                Double.class, gradeId, cie().currentAy().id(), labHead);

            // GST only on the taxable head, and the total carries it.
            assertThat(invoice.get("gst").asDouble()).isEqualTo(Math.round(labAmount * 18) / 100.0);
            assertThat(invoice.get("total").asDouble())
                .isEqualTo(invoice.get("subtotal").asDouble() + invoice.get("gst").asDouble());
            double tuitionLineGst = queryOne(
                "SELECT gst FROM fee_invoice_line WHERE fee_invoice_id = ? AND fee_head_id = ?",
                Double.class, invoiceId, headOf(cie(), "TUITION"));
            assertThat(tuitionLineGst).isZero();

            // No IRN yet (the e-invoice integration is credential-blocked), and a
            // receipt is still issued — a failed IRN must not stop a parent paying.
            assertThat(queryOne("SELECT irn FROM fee_invoice WHERE id = ?", String.class, invoiceId)).isNull();
            var paid = post("/v1/fees/payments", body(
                "schoolId", cie().id(), "feeInvoiceId", invoiceId,
                "amount", invoice.get("total").asDouble(), "gateway", "cash", "method", "cash",
                "idempotencyKey", "cert-fee13-" + invoiceId), token);
            assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get("/v1/fees/invoices/" + invoiceId, token).getBody().get("status").asText())
                .isEqualTo("paid");
        } finally {
            clearCycle(cycle);
            inChainDo(jdbc -> jdbc.update("UPDATE fee_head SET gst_rate_pct = 0 WHERE id = ?", labHead));
        }
    }

    @Test @Tag("P1")
    void cert_FEE_14_dayBookReconcilesToPaymentsAndLedger() {
        String token = accountantToken(cie());
        String today = java.time.LocalDate.now().toString();

        UUID first = createInvoiceIn(cie(), "DAY1-" + UUID.randomUUID().toString().substring(0, 6), 5000);
        UUID second = createInvoiceIn(cie(), "DAY2-" + UUID.randomUUID().toString().substring(0, 6), 3000);
        pay(cie(), first, 5000, "cash");
        pay(cie(), second, 2000, "upi");

        var report = get("/v1/fees/reports/day-book?schoolId=" + cie().id()
            + "&from=" + today + "&to=" + today, token);
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = report.getBody();

        assertThat(body.get("collected").asDouble()).isEqualTo(7000.0);
        assertThat(body.get("byMethod").toString()).contains("cash").contains("upi");
        // The collection total and the ledger's bank movement are computed
        // separately and have to agree.
        assertThat(body.get("reconciles").asBoolean()).isTrue();

        // A reversal on the same day shows up as money going back out.
        UUID paymentId = queryOne("SELECT id FROM payment WHERE fee_invoice_id = ?", UUID.class, second);
        post("/v1/fees/invoices/" + second + "/adjustments", body(
            "schoolId", cie().id(), "kind", "reversal", "amount", 2000.0,
            "reason", "Cheque returned", "paymentId", paymentId), token);

        var afterReversal = get("/v1/fees/reports/day-book?schoolId=" + cie().id()
            + "&from=" + today + "&to=" + today, token).getBody();
        assertThat(afterReversal.get("refunded").asDouble()).isEqualTo(2000.0);
        assertThat(afterReversal.get("net").asDouble()).isEqualTo(5000.0);
        assertThat(afterReversal.get("reconciles").asBoolean()).isTrue();
    }

    @Test @Tag("P1")
    void cert_FEE_15_outstandingDuesReportMatchesInvoiceBalances() {
        String token = accountantToken(cie());
        UUID sectionId = sectionOf(cie(), cie().currentAy().code(), cie().terminalGradeCode(), "A");
        UUID debtor = firstStudentIn(sectionId);
        UUID invoiceId = createInvoiceFor(cie(), debtor,
            "DUE-" + UUID.randomUUID().toString().substring(0, 6), 8000, "2026-08-01");
        pay(cie(), invoiceId, 3000, "cash");

        try {
            var report = get("/v1/fees/reports/outstanding?schoolId=" + cie().id()
                + "&sectionId=" + sectionId, token);
            assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);

            double reported = 0;
            for (var row : report.getBody().get("students")) {
                if (debtor.toString().equals(row.get("studentId").asText())) {
                    reported = row.get("balance").asDouble();
                }
            }
            double actual = queryOne(
                "SELECT COALESCE(sum(total - paid), 0) FROM fee_invoice WHERE student_id = ? " +
                "  AND status IN ('open','partial','overdue')", Double.class, debtor);
            assertThat(reported).isEqualTo(actual);
            assertThat(reported).isGreaterThanOrEqualTo(5000.0);

            // Grade-level totals are the same numbers, aggregated.
            assertThat(report.getBody().get("totalOutstanding").asDouble()).isGreaterThanOrEqualTo(reported);

            // And the year-end clearance predicate reads the same balance.
            var dues = get("/v1/fees/students/" + debtor + "/dues", token).getBody();
            assertThat(dues.get("hasDues").asBoolean()).isTrue();
            assertThat(dues.get("balance").asDouble()).isEqualTo(actual);
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM ledger_entry WHERE source_id IN " +
                    "(SELECT id FROM payment WHERE fee_invoice_id = ?)", invoiceId);
                jdbc.update("DELETE FROM payment WHERE fee_invoice_id = ?", invoiceId);
                jdbc.update("DELETE FROM fee_invoice_line WHERE fee_invoice_id = ?", invoiceId);
                jdbc.update("DELETE FROM fee_invoice WHERE id = ?", invoiceId);
            });
        }
    }

    @Test @Tag("P1")
    void cert_FEE_16_arrearsCarryForwardIntoTheNextYearsOpeningBalance() {
        // Rollover moves a whole school, so this runs against a sandbox school
        // of its own rather than Oakridge — see RolloverSandbox.
        var sandbox = rolloverSandbox("fee16");
        String token = sandboxToken(sandbox);
        UUID debtor = sandbox.firstStudent("R1", "A");
        UUID settled = sandbox.students("R1", "A").get(1);
        try {
            UUID unpaid = UUID.fromString(post("/v1/fees/invoices", body(
                "schoolId", sandbox.schoolId(), "studentId", debtor,
                "invoiceNo", "FEE16-A-" + UUID.randomUUID().toString().substring(0, 8),
                "cycleLabel", "Term 1", "dueOn", "2026-08-20",
                "lines", List.of(body("feeHeadId", sandbox.feeHeadId(), "description", "Tuition",
                    "amount", 9000.0, "discount", 0.0, "gst", 0.0))), token)
                .getBody().get("id").asText());
            post("/v1/fees/payments", body(
                "schoolId", sandbox.schoolId(), "feeInvoiceId", unpaid, "amount", 3500.0,
                "gateway", "manual", "method", "cash", "idempotencyKey", "fee16-" + UUID.randomUUID()), token);

            UUID paid = UUID.fromString(post("/v1/fees/invoices", body(
                "schoolId", sandbox.schoolId(), "studentId", settled,
                "invoiceNo", "FEE16-B-" + UUID.randomUUID().toString().substring(0, 8),
                "cycleLabel", "Term 1", "dueOn", "2026-08-20",
                "lines", List.of(body("feeHeadId", sandbox.feeHeadId(), "description", "Tuition",
                    "amount", 2000.0, "discount", 0.0, "gst", 0.0))), token)
                .getBody().get("id").asText());
            post("/v1/fees/payments", body(
                "schoolId", sandbox.schoolId(), "feeInvoiceId", paid, "amount", 2000.0,
                "gateway", "manual", "method", "cash", "idempotencyKey", "fee16b-" + UUID.randomUUID()), token);

            var run = post("/v1/rollover/runs", body(
                "schoolId", sandbox.schoolId(), "fromAcademicYearId", sandbox.sourceAyId(),
                "toAcademicYearId", sandbox.targetAyId(), "runKey", "fee16",
                "startedByStaffId", sandbox.principalStaffId()), token);
            UUID runId = UUID.fromString(run.getBody().get("id").asText());
            post("/v1/rollover/runs/" + runId + "/clone-structure", null, token);
            post("/v1/rollover/runs/" + runId + "/allocate", null, token);
            var committed = post("/v1/rollover/runs/" + runId + "/commit", body(), token);
            assertThat(committed.getBody().get("arrearsCarried").asDouble()).isEqualTo(5500.0);
            post("/v1/rollover/runs/" + runId + "/activate",
                body("actingStaffId", sandbox.principalStaffId()), token);

            // What the family still owes is now an invoice in the year they are
            // being asked to pay it, under its own head.
            var invoices = get("/v1/fees/invoices?studentId=" + debtor, token).getBody();
            var opening = java.util.stream.StreamSupport.stream(invoices.spliterator(), false)
                .filter(node -> node.get("cycleLabel").asText().startsWith("Opening balance"))
                .findFirst().orElseThrow();
            assertThat(opening.get("total").asDouble()).isEqualTo(5500.0);
            assertThat(opening.get("status").asText()).isEqualTo("open");
            assertThat(queryOne(
                "SELECT fh.code FROM fee_invoice_line fil JOIN fee_head fh ON fh.id = fil.fee_head_id " +
                "WHERE fil.fee_invoice_id = ?", String.class,
                UUID.fromString(opening.get("id").asText()))).isEqualTo("ARREARS");

            // And it is not owed twice: last year's invoice is marked as moved,
            // so the outstanding report counts the amount once.
            assertThat(queryOne("SELECT status FROM fee_invoice WHERE id = ?", String.class, unpaid))
                .isEqualTo("carried_forward");
            var outstanding = get("/v1/fees/reports/outstanding?schoolId=" + sandbox.schoolId(), token)
                .getBody();
            assertThat(outstanding.get("totalOutstanding").asDouble()).isEqualTo(5500.0);
            assertThat(outstanding.get("studentsWithDues").asInt()).isEqualTo(1);

            // A family that paid up starts the year clean.
            assertThat(count(
                "SELECT count(*) FROM fee_invoice WHERE student_id = ? AND academic_year_id = ?",
                settled, sandbox.targetAyId())).isZero();
        } finally {
            dropSandbox(sandbox);
        }
    }

    @Test @Tag("P2")
    void cert_FEE_17_simultaneousPaymentsDoNotOverCredit() throws Exception {
        String token = accountantToken(cbse());
        UUID invoiceId = createInvoice("RACE-" + UUID.randomUUID().toString().substring(0, 8), 10000);

        // Both parents pay the full amount at the same moment.
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var results = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
        Runnable payer = () -> {
            try {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                var response = post("/v1/fees/payments", body(
                    "schoolId", cbse().id(), "feeInvoiceId", invoiceId, "amount", 10000.0,
                    "gateway", "razorpay", "method", "upi",
                    "idempotencyKey", "cert-race-" + Thread.currentThread().getName() + "-" + invoiceId), token);
                results.add(response.getStatusCode().value());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        Thread one = new Thread(payer, "payer-1");
        Thread two = new Thread(payer, "payer-2");
        one.start();
        two.start();
        one.join();
        two.join();

        assertThat(results).allMatch(status -> status == 200);
        var invoice = get("/v1/fees/invoices/" + invoiceId, token).getBody();

        // The bill is settled once. The second payment is held as an advance
        // rather than over-crediting it.
        assertThat(invoice.get("paid").asDouble()).isEqualTo(10000.0);
        assertThat(invoice.get("status").asText()).isEqualTo("paid");
        assertThat(count("SELECT count(*) FROM payment WHERE fee_invoice_id = ?", invoiceId)).isEqualTo(2);
        assertThat(queryOne("SELECT advance_amount FROM fee_invoice WHERE id = ?", Double.class, invoiceId))
            .isEqualTo(10000.0);
        // Money held but not earned sits in a liability account, not income.
        assertThat(count("SELECT count(*) FROM ledger_entry WHERE account_code = 'ADVANCE' " +
            "AND source_id IN (SELECT id FROM payment WHERE fee_invoice_id = ?)", invoiceId)).isEqualTo(1);
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

    // ---------------------------------------------------------------- helpers

    private UUID headOf(com.schoolsoft.certification.support.CertificationFixture.SchoolSeed school,
                        String code) {
        return queryOne("SELECT id FROM fee_head WHERE school_id = ? AND code = ?",
            UUID.class, school.id(), code);
    }

    private org.springframework.http.ResponseEntity<com.fasterxml.jackson.databind.JsonNode> generate(
        com.schoolsoft.certification.support.CertificationFixture.SchoolSeed school,
        UUID gradeId, String cycleLabel, String dueOn, String token
    ) {
        return post("/v1/fees/generate", body(
            "schoolId", school.id(), "academicYearId", school.currentAy().id(), "gradeId", gradeId,
            "cycleLabel", cycleLabel, "dueOn", dueOn, "runByStaffId", school.accountantStaffId()), token);
    }

    /** Which sources the student's invoice lines came from, for that cycle. */
    private java.util.List<String> lineSources(UUID studentId, String cycleLabel) {
        return queryList(
            "SELECT l.source FROM fee_invoice_line l JOIN fee_invoice i ON i.id = l.fee_invoice_id " +
            "WHERE i.student_id = ? AND i.cycle_label = ?", String.class, studentId, cycleLabel);
    }

    private boolean journalBalances(String sourceType, UUID sourceId) {
        Double debits = queryOne("SELECT COALESCE(sum(debit), 0) FROM ledger_entry " +
            "WHERE source_type = ? AND source_id = ?", Double.class, sourceType, sourceId);
        Double credits = queryOne("SELECT COALESCE(sum(credit), 0) FROM ledger_entry " +
            "WHERE source_type = ? AND source_id = ?", Double.class, sourceType, sourceId);
        return debits > 0 && Math.abs(debits - credits) < 0.005;
    }

    /** A pair of siblings from the fixture's family seeding, eldest first. */
    private UUID[] siblingPair(com.schoolsoft.certification.support.CertificationFixture.SchoolSeed school) {
        var pair = inChain(jdbc -> jdbc.query(
            "SELECT gs.student_id, e.starts_on, g.code AS grade_code FROM guardian_student gs " +
            "JOIN enrolment e ON e.student_id = gs.student_id AND e.status = 'active' " +
            "JOIN section sec ON sec.id = e.section_id JOIN grade g ON g.id = sec.grade_id " +
            "WHERE gs.guardian_id = (" +
            "  SELECT guardian_id FROM guardian_student gs2 " +
            "  JOIN student st ON st.id = gs2.student_id WHERE st.school_id = ? " +
            "  GROUP BY guardian_id HAVING count(*) > 1 LIMIT 1) " +
            "ORDER BY g.sort_order DESC",
            (rs, i) -> new Object[]{ UUID.fromString(rs.getString("student_id")), rs.getString("grade_code") },
            school.id()));
        assertThat(pair).hasSizeGreaterThanOrEqualTo(2);
        siblingGrades.put((UUID) pair.get(0)[0], (String) pair.get(0)[1]);
        siblingGrades.put((UUID) pair.get(1)[0], (String) pair.get(1)[1]);
        return new UUID[]{ (UUID) pair.get(0)[0], (UUID) pair.get(1)[0] };
    }

    private final java.util.Map<UUID, String> siblingGrades = new java.util.HashMap<>();

    private String gradeCodeOf(UUID studentId) {
        return siblingGrades.get(studentId);
    }

    private void clearCycle(String cycleLabel) {
        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM ledger_entry WHERE source_id IN (SELECT p.id FROM payment p " +
                "JOIN fee_invoice i ON i.id = p.fee_invoice_id WHERE i.cycle_label = ?)", cycleLabel);
            jdbc.update("DELETE FROM payment WHERE fee_invoice_id IN " +
                "(SELECT id FROM fee_invoice WHERE cycle_label = ?)", cycleLabel);
            jdbc.update("DELETE FROM fee_invoice_line WHERE fee_invoice_id IN " +
                "(SELECT id FROM fee_invoice WHERE cycle_label = ?)", cycleLabel);
            jdbc.update("DELETE FROM fee_invoice WHERE cycle_label = ?", cycleLabel);
            jdbc.update("DELETE FROM fee_schedule_run WHERE cycle_label = ?", cycleLabel);
        });
    }

    private void clearDunning(UUID invoiceId) {
        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM dunning_event WHERE fee_invoice_id = ?", invoiceId);
            jdbc.update("DELETE FROM ledger_entry WHERE source_id IN " +
                "(SELECT id FROM fee_adjustment WHERE fee_invoice_id = ?)", invoiceId);
            jdbc.update("DELETE FROM fee_adjustment WHERE fee_invoice_id = ?", invoiceId);
            jdbc.update("DELETE FROM fee_invoice_line WHERE fee_invoice_id = ?", invoiceId);
            jdbc.update("DELETE FROM fee_invoice WHERE id = ?", invoiceId);
            jdbc.update("DELETE FROM dunning_policy WHERE school_id = ?", cbse().id());
        });
    }

    private UUID createInvoiceFor(UUID studentId, String invoiceNo, double amount, String dueOn) {
        return createInvoiceFor(cbse(), studentId, invoiceNo, amount, dueOn);
    }

    private UUID createInvoiceFor(
        com.schoolsoft.certification.support.CertificationFixture.SchoolSeed school,
        UUID studentId, String invoiceNo, double amount, String dueOn
    ) {
        var created = post("/v1/fees/invoices", body(
            "schoolId", school.id(), "studentId", studentId, "invoiceNo", invoiceNo,
            "cycleLabel", "Certification " + invoiceNo, "dueOn", dueOn,
            "lines", List.of(Map.of("feeHeadId", headOf(school, "TUITION"), "description", "Tuition",
                "amount", amount, "discount", 0, "gst", 0))), accountantToken(school));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }

    private UUID createInvoiceIn(
        com.schoolsoft.certification.support.CertificationFixture.SchoolSeed school,
        String invoiceNo, double amount
    ) {
        UUID studentId = firstStudentIn(currentFocusSection(school));
        return createInvoiceFor(school, studentId, invoiceNo, amount,
            java.time.LocalDate.now().plusDays(10).toString());
    }

    private void pay(com.schoolsoft.certification.support.CertificationFixture.SchoolSeed school,
                     UUID invoiceId, double amount, String method) {
        var paid = post("/v1/fees/payments", body(
            "schoolId", school.id(), "feeInvoiceId", invoiceId, "amount", amount,
            "gateway", "cash".equals(method) ? "cash" : "razorpay", "method", method,
            "idempotencyKey", "cert-pay-" + method + "-" + invoiceId), accountantToken(school));
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
