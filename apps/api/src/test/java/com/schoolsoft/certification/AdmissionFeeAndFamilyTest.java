package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * What ADM-10 and ADM-13 left open, pinned: the admission fee after the
 * application has moved on or closed, and a family the chain already knows.
 *
 * <p>Not {@code cert_} scenarios — the catalogue has no row for these, and
 * {@link CatalogueSyncTest} owns that namespace. They run in {@code harness},
 * so the blocking gate covers them.</p>
 */
@Tag("harness")
class AdmissionFeeAndFamilyTest extends AbstractCertificationTest {

    private static final List<String> TO_FEE = List.of("application_started", "document_pending", "fee_pending");

    @Test
    @DisplayName("a cheque that bounces after the application moved on stops the next step, and admissions is shown it")
    void bounceAfterTheMoveIsSurfacedAndStopsProgress() {
        String registrar = registrarToken(cbse());
        String accountant = accountantToken(cbse());
        UUID gradeId = gradeOf(cbse(), "2");
        priceAdmission(gradeId, 3000.0);
        Applicant a = apply("Bounce", gradeId, phone(), null);
        try {
            walk(a, TO_FEE);
            UUID invoiceId = invoiceOf(a);
            UUID cheque = pay(invoiceId, 3000.0, "cheque");
            assertThat(move(a, "review").getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(owing()).doesNotContain(a.id().toString());

            // The cheque comes back after the file has moved on.
            assertThat(post("/v1/fees/invoices/" + invoiceId + "/adjustments", body(
                "schoolId", cbse().id(), "kind", "reversal", "amount", 3000.0,
                "reason", "Cheque returned unpaid", "paymentId", cheque,
                "approvedByStaffId", cbse().accountantStaffId()), accountant).getStatusCode())
                .isEqualTo(HttpStatus.OK);

            // Admissions is told, on its own screen's feed...
            var feed = get("/v1/admissions/fees/owing?schoolId=" + cbse().id(), registrar).getBody();
            assertThat(feed.findValuesAsText("applicationId")).contains(a.id().toString());
            for (var row : feed) {
                if (row.get("applicationId").asText().equals(a.id().toString())) {
                    assertThat(row.get("state").asText()).isEqualTo("review");
                    assertThat(row.get("outstanding").asDouble()).isEqualTo(3000.0);
                }
            }
            // ...the accountant sees an applicant in the dues report, apart from
            // the students...
            var report = get("/v1/fees/reports/outstanding?schoolId=" + cbse().id(), accountant).getBody();
            assertThat(report.get("applicants").findValuesAsText("applicationId")).contains(a.id().toString());
            assertThat(report.get("applicantsOutstanding").asDouble()).isGreaterThanOrEqualTo(3000.0);
            assertThat(report.get("students").findValuesAsText("name")).doesNotContain(a.name() + " Probe");

            // ...and the next step forward is refused, in the same words as at
            // the fee stage.
            var blocked = move(a, "test_scheduled");
            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(blocked.getBody().toString()).contains("admission fee");
            assertThat(stateOf(a)).isEqualTo("review");

            // Paying settles it wherever the application stands.
            pay(invoiceId, 3000.0, "cash");
            assertThat(owing()).doesNotContain(a.id().toString());
            assertThat(move(a, "test_scheduled").getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get("/v1/fees/reports/outstanding?schoolId=" + cbse().id(), accountant).getBody()
                .get("applicants").findValuesAsText("applicationId")).doesNotContain(a.id().toString());
        } finally {
            cleanUp(a);
        }
    }

    @Test
    @DisplayName("closing an application cancels an unpaid fee and leaves a paid one for accounts to refund")
    void closingAnApplicationSettlesWhatBecomesOfItsFee() {
        String registrar = registrarToken(cbse());
        String accountant = accountantToken(cbse());
        UUID gradeId = gradeOf(cbse(), "2");
        priceAdmission(gradeId, 3000.0);
        Applicant unpaid = apply("Unpaid", gradeId, phone(), null);
        Applicant paid = apply("Paid", gradeId, phone(), null);
        try {
            // Turned away owing: nobody chases a family the school rejected.
            walk(unpaid, TO_FEE);
            assertThat(move(unpaid, "rejected").getStatusCode()).isEqualTo(HttpStatus.OK);
            var cancelled = get(feePath(unpaid), registrar).getBody();
            assertThat(cancelled.get("status").asText()).isEqualTo("cancelled");
            assertThat(get("/v1/fees/reports/outstanding?schoolId=" + cbse().id(), accountant).getBody()
                .get("applicants").findValuesAsText("applicationId")).doesNotContain(unpaid.id().toString());

            // Turned away having paid: the money stays put until somebody decides.
            walk(paid, TO_FEE);
            UUID invoiceId = invoiceOf(paid);
            pay(invoiceId, 1000.0, "cash");
            pay(invoiceId, 2000.0, "upi");

            // Not while the family is still in the funnel.
            assertThat(post(feePath(paid) + "/refund", Map.of("reason", "Changed their mind"), accountant)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            move(paid, "review");
            assertThat(move(paid, "rejected").getStatusCode()).isEqualTo(HttpStatus.OK);
            var kept = get(feePath(paid), registrar).getBody();
            assertThat(kept.get("status").asText()).isEqualTo("paid");
            assertThat(kept.get("refunded").asDouble()).isZero();

            // Rejecting is admissions' call; paying money back is not.
            assertThat(post(feePath(paid) + "/refund", Map.of("reason", "Not offered a seat"), registrar)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(post(feePath(paid) + "/refund", Map.of("reason", " "), accountant).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

            var refunded = post(feePath(paid) + "/refund", Map.of("reason", "Not offered a seat"), accountant);
            assertThat(refunded.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(refunded.getBody().get("status").asText()).isEqualTo("refunded");
            assertThat(refunded.getBody().get("refunded").asDouble()).isEqualTo(3000.0);
            assertThat(refunded.getBody().get("paid").asDouble()).isZero();

            // One refund per payment, each balanced, and the money went out once.
            assertThat(count("SELECT count(*) FROM fee_adjustment WHERE fee_invoice_id = ? AND kind = 'refund'",
                invoiceId)).isEqualTo(2);
            assertThat(queryOne("SELECT COALESCE(sum(debit) - sum(credit), 0) FROM ledger_entry WHERE source_id IN "
                + "(SELECT id FROM fee_adjustment WHERE fee_invoice_id = ?)", Double.class, invoiceId)).isZero();
            var again = post(feePath(paid) + "/refund", Map.of("reason", "Not offered a seat"), accountant);
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(count("SELECT count(*) FROM fee_adjustment WHERE fee_invoice_id = ? AND kind = 'refund'",
                invoiceId)).isEqualTo(2);
        } finally {
            cleanUp(unpaid);
            cleanUp(paid);
        }
    }

    @Test
    @DisplayName("a family the chain already knows is admitted, and given a login on something that is free")
    void aFamilyTheChainAlreadyKnowsIsStillAdmitted() {
        String registrar = registrarToken(cbse());
        UUID gradeId = gradeOf(cbse(), "2");
        UUID section = sectionOf(cbse(), cbse().currentAy().code(), "2", "A");

        // The number signs somebody in at the chain's other school — a row this
        // school's session cannot even see.
        String takenPhone = phone();
        String takenToo = phone();
        UUID elsewhere = UUID.randomUUID();
        UUID staffHere = UUID.randomUUID();
        inChainDo(jdbc -> {
            jdbc.update("INSERT INTO user_account (id, school_id, subject_type, subject_id, phone) "
                + "VALUES (?, ?, 'guardian', ?, ?)", elsewhere, cie().id(), UUID.randomUUID(), takenPhone);
            // And one that is a member of staff's, here.
            jdbc.update("INSERT INTO user_account (id, school_id, subject_type, subject_id, phone) "
                + "VALUES (?, ?, 'staff', ?, ?)", staffHere, cbse().id(), UUID.randomUUID(), takenToo);
        });
        String email = "family." + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        Applicant withEmail = apply("Sister", gradeId, takenPhone, email);
        Applicant phoneOnly = apply("Staffchild", gradeId, takenToo, null);
        try {
            // Phone taken, email free: admitted, and the login is the email.
            walk(withEmail, List.of("application_started", "document_pending", "fee_pending", "review",
                "test_scheduled", "test_done", "offered", "accepted"));
            // Enrolled is reached by enrolling. Moved there by hand, the
            // application used to say enrolled with no child behind it.
            assertThat(move(withEmail, "enrolled").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(stateOf(withEmail)).isEqualTo("accepted");
            assertThat(get(appPath(withEmail) + "/moves", registrar).getBody().toString())
                .doesNotContain("enrolled");

            var enrolled = post(appPath(withEmail) + "/enrol", Map.of("sectionId", section), registrar);
            assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(enrolled.getBody().get("guardianHasLogin").asBoolean()).isTrue();
            var login = get(appPath(withEmail) + "/guardian-login", registrar).getBody();
            assertThat(login.get("signInEmail").asText()).isEqualTo(email);
            assertThat(login.has("signInPhone")).isFalse();
            assertThat(login.get("contactPhone").asText()).isEqualTo(takenPhone);
            // The account that already held the number is untouched.
            assertThat(queryOne("SELECT school_id FROM user_account WHERE phone = ?", UUID.class, takenPhone))
                .isEqualTo(cie().id());

            // Nothing free at all: still admitted, and the office is told.
            walk(phoneOnly, List.of("application_started", "document_pending", "fee_pending", "review",
                "test_scheduled", "test_done", "offered", "accepted"));
            var noLogin = post(appPath(phoneOnly) + "/enrol", Map.of("sectionId", section), registrar);
            assertThat(noLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(noLogin.getBody().get("guardianHasLogin").asBoolean()).isFalse();
            UUID child = UUID.fromString(noLogin.getBody().get("studentId").asText());
            assertThat(count("SELECT count(*) FROM guardian_student WHERE student_id = ? AND is_primary", child))
                .isEqualTo(1);
            assertThat(get(appPath(phoneOnly) + "/guardian-login", registrar).getBody().get("hasLogin").asBoolean())
                .isFalse();

            // Another taken number is refused in words; a free address works.
            var stillTaken = put(appPath(phoneOnly) + "/guardian-login", Map.of("identifier", takenPhone), registrar);
            assertThat(stillTaken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(stillTaken.getBody().toString()).contains("already signs somebody in");
            String fresh = "parent." + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
            var given = put(appPath(phoneOnly) + "/guardian-login", Map.of("identifier", fresh), registrar);
            assertThat(given.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(given.getBody().get("hasLogin").asBoolean()).isTrue();

            // And it is a real way in: the guardian signs in with it and finds the child.
            post("/v1/auth/otp/start", Map.of("identifier", fresh, "chainSlug", seed.chainSlug()), null);
            var signedIn = post("/v1/auth/otp/verify",
                Map.of("identifier", fresh, "chainSlug", seed.chainSlug(), "code", "000000"), null);
            assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get("/v1/people/guardians/" + given.getBody().get("guardianId").asText() + "/students",
                signedIn.getBody().get("accessToken").asText()).getBody().findValuesAsText("id"))
                .containsExactly(child.toString());

            // Once is enough.
            assertThat(put(appPath(phoneOnly) + "/guardian-login", Map.of("identifier", "x@example.test"),
                registrar).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        } finally {
            cleanUp(withEmail);
            cleanUp(phoneOnly);
            inChainDo(jdbc -> jdbc.update("DELETE FROM user_account WHERE id IN (?, ?)", elsewhere, staffHere));
        }
    }

    // ---------------------------------------------------------------- helpers

    private record Applicant(UUID id, String name) {}

    private static String phone() {
        return "919666" + (100000 + (int) (Math.random() * 800000));
    }

    private void priceAdmission(UUID gradeId, double amount) {
        assertThat(put("/v1/admissions/fees", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
            "gradeId", gradeId, "amount", amount), registrarToken(cbse())).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }

    private Applicant apply(String role, UUID gradeId, String guardianPhone, String guardianEmail) {
        String name = role + UUID.randomUUID().toString().substring(0, 6);
        var created = post("/v1/admissions/applications", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(), "gradeId", gradeId,
            "applicantFirstName", name, "applicantLastName", "Probe",
            "applicantDob", "2019-04-04", "applicantGender", "female",
            "guardianName", "Probe Guardian", "guardianPhone", guardianPhone, "guardianEmail", guardianEmail,
            "source", "walkin"), registrarToken(cbse()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Applicant(UUID.fromString(created.getBody().get("id").asText()), name);
    }

    private String appPath(Applicant a) { return "/v1/admissions/applications/" + a.id(); }

    private String feePath(Applicant a) { return appPath(a) + "/fee"; }

    private org.springframework.http.ResponseEntity<com.fasterxml.jackson.databind.JsonNode> move(
            Applicant a, String toState) {
        return post(appPath(a) + "/transition", Map.of("toState", toState), registrarToken(cbse()));
    }

    private void walk(Applicant a, List<String> states) {
        for (String state : states) {
            assertThat(move(a, state).getStatusCode()).as(a.name() + " to " + state).isEqualTo(HttpStatus.OK);
        }
    }

    private String stateOf(Applicant a) {
        return queryOne("SELECT state FROM admission_application WHERE id = ?", String.class, a.id());
    }

    private UUID invoiceOf(Applicant a) {
        return UUID.fromString(get(feePath(a), registrarToken(cbse())).getBody().get("invoiceId").asText());
    }

    private UUID pay(UUID invoiceId, double amount, String method) {
        var paid = post("/v1/fees/payments", body(
            "schoolId", cbse().id(), "feeInvoiceId", invoiceId, "amount", amount,
            "gateway", method, "method", method, "idempotencyKey", "probe-" + UUID.randomUUID()),
            accountantToken(cbse()));
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(paid.getBody().get("id").asText());
    }

    private List<String> owing() {
        return get("/v1/admissions/fees/owing?schoolId=" + cbse().id(), registrarToken(cbse())).getBody()
            .findValuesAsText("applicationId");
    }

    /** Everything one probe applicant left behind, and the price list with it. */
    private void cleanUp(Applicant a) {
        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM admission_fee WHERE school_id = ?", cbse().id());
            String invoices = "(SELECT id FROM fee_invoice WHERE admission_application_id = ?)";
            jdbc.update("DELETE FROM ledger_entry WHERE source_id IN (SELECT id FROM payment WHERE "
                + "fee_invoice_id IN " + invoices + ")", a.id());
            jdbc.update("DELETE FROM ledger_entry WHERE source_id IN (SELECT id FROM fee_adjustment WHERE "
                + "fee_invoice_id IN " + invoices + ")", a.id());
            jdbc.update("DELETE FROM fee_adjustment WHERE fee_invoice_id IN " + invoices, a.id());
            jdbc.update("DELETE FROM payment WHERE fee_invoice_id IN " + invoices, a.id());
            jdbc.update("DELETE FROM fee_invoice_line WHERE fee_invoice_id IN " + invoices, a.id());
            jdbc.update("DELETE FROM fee_invoice WHERE admission_application_id = ?", a.id());
            jdbc.update("DELETE FROM fee_head h WHERE h.school_id = ? AND h.code = 'ADMISSION' AND NOT EXISTS "
                + "(SELECT 1 FROM fee_invoice_line l WHERE l.fee_head_id = h.id)", cbse().id());
            String students = "(SELECT id FROM student WHERE first_name = ?)";
            jdbc.update("DELETE FROM user_account WHERE subject_type = 'guardian' AND subject_id IN "
                + "(SELECT guardian_id FROM guardian_student WHERE student_id IN " + students + ")", a.name());
            List<UUID> guardians = jdbc.queryForList(
                "SELECT guardian_id FROM guardian_student WHERE student_id IN " + students, UUID.class, a.name());
            jdbc.update("DELETE FROM guardian_student WHERE student_id IN " + students, a.name());
            guardians.forEach(g -> jdbc.update("DELETE FROM guardian WHERE id = ?", g));
            jdbc.update("DELETE FROM enrolment WHERE student_id IN " + students, a.name());
            jdbc.update("UPDATE admission_application SET converted_student_id = NULL WHERE id = ?", a.id());
            jdbc.update("DELETE FROM student WHERE first_name = ?", a.name());
            jdbc.update("DELETE FROM admission_event WHERE application_id = ?", a.id());
            jdbc.update("DELETE FROM admission_application WHERE id = ?", a.id());
        });
    }
}
