package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-ADM — leads & admissions funnel. */
class AdmissionsCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_ADM_01_publicEnquiryCreatesLeadAndAcknowledgesTheGuardian() {
        // Digits only: the tracking lookup round-trips the phone through a query parameter.
        String phone = "919222" + (100000 + (int) (Math.random() * 800000));
        String email = "ack." + UUID.randomUUID().toString().substring(0, 6) + "@example.test";

        var applied = post("/v1/public/schools/" + seed.chainSlug() + "/" + cbse().slug() + "/admissions/apply",
            body("applicantFirstName", "Ira", "applicantLastName", "Nair",
                "applicantDob", "2020-05-11", "applicantGender", "female",
                "gradeId", gradeOf(cbse(), "1"), "guardianName", "Meera Nair",
                "guardianPhone", phone, "guardianEmail", email), null);
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        String applicationNo = applied.getBody().get("applicationNo").asText();

        var tracked = get("/v1/public/schools/" + seed.chainSlug() + "/" + cbse().slug()
            + "/admissions/track?applicationNo=" + applicationNo + "&guardianPhone=" + phone, null);
        assertThat(tracked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tracked.getBody().get("state").asText()).isEqualTo("lead");
        assertThat(tracked.getBody().get("source").asText()).isEqualTo("website");
        // Tracking answers with the status, not the record — no internal ids.
        assertThat(tracked.getBody().has("id")).isFalse();
        assertThat(tracked.getBody().has("guardianPhone")).isFalse();
        assertThat(tracked.getBody().has("applicantDob")).isFalse();
        UUID applicationId = queryOne(
            "SELECT id FROM admission_application WHERE application_no = ? AND school_id = ?",
            UUID.class, applicationNo, cbse().id());

        // The acknowledgement is addressed to the applicant, not to a guardian:
        // the family has no guardian row and no login until conversion, so the
        // details they typed into the form are the only ones the school holds.
        List<String> channels = queryList(
            "SELECT channel FROM notification_dispatch WHERE recipient_type = 'applicant' "
            + "AND recipient_id = ? AND template_code = 'admission_received' ORDER BY channel",
            String.class, applicationId);
        assertThat(channels).containsExactly("email", "sms");

        // Nothing to push to and no WhatsApp opt-in: a form is not consent to
        // an approved-template message on a channel §10 gates separately.
        assertThat(channels).doesNotContain("push", "whatsapp");

        assertThat(count("SELECT count(*) FROM notification_dispatch WHERE recipient_id = ? "
            + "AND status = 'sent' AND sent_at IS NOT NULL", applicationId)).isEqualTo(2);

        // It carries the number the family will quote back when they track it.
        String variables = queryOne(
            "SELECT variables::text FROM notification_dispatch WHERE recipient_id = ? LIMIT 1",
            String.class, applicationId);
        assertThat(variables).contains(applicationNo).contains("Ira Nair");

        // The acknowledgement is tied to the application, so the office can see
        // what went out without reading the dispatch table by recipient type.
        assertThat(count("SELECT count(*) FROM notification_dispatch WHERE related_type = 'admission' "
            + "AND related_id = ?", applicationId)).isEqualTo(2);
    }

    @Test @Tag("P1")
    void cert_ADM_02_walkInLeadEntersTheSamePipelineWithItsOwnSource() {
        var created = createApplication("walkin", "WALKIN-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().get("state").asText()).isEqualTo("lead");
        assertThat(created.getBody().get("source").asText()).isEqualTo("walkin");

        var webApplication = applyThroughPublicSite();
        assertThat(webApplication.get("source")).isEqualTo("website");

        // Same pipeline, different attribution.
        var leads = get("/v1/admissions/applications?schoolId=" + cbse().id() + "&state=lead",
            registrarToken(cbse())).getBody();
        List<String> sources = new ArrayList<>();
        leads.forEach(node -> sources.add(node.get("source").asText()));
        assertThat(sources).contains("walkin", "website");
    }

    @Test @Tag("P1")
    @Disabled("GAP-16 — /admissions/track returns the stage, but pending documents are loose JSONB with "
        + "no verification state, so the guardian cannot be shown what is outstanding or what to do next.")
    void cert_ADM_03_guardianTracksApplicationWithoutAnAccount() {
    }

    @Test @Tag("P1")
    void cert_ADM_04_happyPathTransitionsAreRecordedWithActorAndTimestamp() {
        String token = registrarToken(cbse());
        var application = createApplication("walkin", "FUNNEL-" + UUID.randomUUID().toString().substring(0, 8));
        UUID id = UUID.fromString(application.getBody().get("id").asText());

        List<String> path = List.of("application_started", "document_pending", "fee_pending", "review",
            "test_scheduled", "test_done", "offered", "accepted");
        for (String state : path) {
            var moved = post("/v1/admissions/applications/" + id + "/transition", Map.of("toState", state), token);
            assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(moved.getBody().get("state").asText()).isEqualTo(state);
        }

        var events = get("/v1/admissions/applications/" + id + "/events", token).getBody();
        List<String> recorded = new ArrayList<>();
        events.forEach(node -> recorded.add(node.get("toState").asText()));
        assertThat(recorded).containsSubsequence(path.toArray(new String[0]));
        events.forEach(node -> assertThat(node.get("occurredAt").asText()).isNotBlank());

        long withActor = count(
            "SELECT count(*) FROM admission_event WHERE application_id = ? AND actor_user_id IS NOT NULL", id);
        assertThat(withActor).isEqualTo(path.size());
    }

    @Test @Tag("P1")
    void cert_ADM_05_invalidTransitionIsRejectedByTheServer() {
        String token = registrarToken(cbse());
        var application = createApplication("walkin", "SM-" + UUID.randomUUID().toString().substring(0, 8));
        UUID id = UUID.fromString(application.getBody().get("id").asText());

        // The leap the UI used to be the only thing preventing.
        var leap = post("/v1/admissions/applications/" + id + "/transition",
            Map.of("toState", "enrolled"), token);
        assertThat(leap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(queryOne("SELECT state FROM admission_application WHERE id = ?", String.class, id))
            .isEqualTo("lead");

        // The refusal says what is possible from here rather than only that this isn't.
        var offered = get("/v1/admissions/applications/" + id + "/moves", token).getBody();
        List<String> allowed = new ArrayList<>();
        offered.forEach(node -> allowed.add(node.asText()));
        assertThat(allowed).containsExactlyInAnyOrder("application_started", "rejected", "lapsed");

        // A state that is not a state at all is refused the same way.
        assertThat(post("/v1/admissions/applications/" + id + "/transition",
            Map.of("toState", "graduated"), token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // A legal move lands, and repeating it is a no-op rather than a failure
        // — a retried request must not fail because the first attempt worked.
        assertThat(post("/v1/admissions/applications/" + id + "/transition",
            Map.of("toState", "application_started"), token).getStatusCode()).isEqualTo(HttpStatus.OK);
        var again = post("/v1/admissions/applications/" + id + "/transition",
            Map.of("toState", "application_started"), token);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM admission_event WHERE application_id = ? AND to_state = ?",
            id, "application_started")).isEqualTo(1);

        // And /enrol is not a way around the machine: a seat is confirmed from
        // 'accepted', never from wherever the application happens to stand.
        var early = post("/v1/admissions/applications/" + id + "/enrol",
            Map.of("sectionId", sectionOf(cbse(), cbse().currentAy().code(), "1", "A")), token);
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("SELECT count(*) FROM admission_application WHERE id = ? AND converted_student_id IS NOT NULL",
            id)).isZero();
    }

    @Test @Tag("P2")
    @Disabled("No cohort test scheduling, no bulk score capture, and no rank list against seats per grade. "
        + "New gap found in Phase 0 (adjacent to GAP-11).")
    void cert_ADM_06_entranceTestCohortIsScoredInBulkAndRanked() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-11 — offer_expires_on is stored but no job expires an offer or returns the seat to the pool.")
    void cert_ADM_07_expiredOfferLapsesAndReturnsTheSeat() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-11 — no waitlist promotion on decline.")
    void cert_ADM_08_waitlistIsPromotedWhenAnOfferIsDeclined() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-11 — no duplicate-lead detection by guardian phone; a second enquiry creates a second "
        + "funnel entry.")
    void cert_ADM_09_duplicateEnquiryFromTheSameGuardianIsMerged() {
    }

    @Test @Tag("P1")
    void cert_ADM_10_seatConfirmationCreatesStudentGuardianAndEnrolmentTransactionally() {
        String token = registrarToken(cbse());
        String marker = "Adm10" + UUID.randomUUID().toString().substring(0, 6);
        String phone = "919333" + (100000 + (int) (Math.random() * 800000));
        UUID section = sectionOf(cbse(), cbse().currentAy().code(), "6", "A");

        UUID applicationId = UUID.fromString(post("/v1/admissions/applications", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(), "gradeId", gradeOf(cbse(), "6"),
            "applicationNo", "ADM10-" + UUID.randomUUID().toString().substring(0, 8),
            "applicantFirstName", marker, "applicantLastName", "Kulkarni",
            "applicantDob", "2015-08-19", "applicantGender", "female",
            "guardianName", "Asha Kulkarni", "guardianPhone", phone,
            "guardianEmail", marker.toLowerCase() + "@example.test",
            "source", "walkin"), token).getBody().get("id").asText());
        for (String state : List.of("application_started", "document_pending", "fee_pending", "review",
                "test_scheduled", "test_done", "offered", "accepted")) {
            post("/v1/admissions/applications/" + applicationId + "/transition",
                Map.of("toState", state), token);
        }
        long seatsBefore = count("SELECT count(*) FROM enrolment WHERE section_id = ?", section);

        // The link between guardian and child is the last row conversion writes
        // before the application's own state, which makes a refusal there the
        // one that would leave the most behind if the conversion were not one
        // transaction. Nothing in the product refuses it, so the scenario does:
        // a trigger that fires for this applicant only.
        inChainDo(jdbc -> {
            jdbc.execute("CREATE OR REPLACE FUNCTION adm10_refuse_link() RETURNS trigger AS $$ BEGIN "
                + "IF EXISTS (SELECT 1 FROM student WHERE id = NEW.student_id AND first_name = '" + marker + "') "
                + "THEN RAISE EXCEPTION 'ADM-10 refuses this link' USING ERRCODE = '23514'; END IF; "
                + "RETURN NEW; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER adm10_refuse_link BEFORE INSERT ON guardian_student "
                + "FOR EACH ROW EXECUTE FUNCTION adm10_refuse_link()");
        });

        try {
            var refused = post("/v1/admissions/applications/" + applicationId + "/enrol",
                Map.of("sectionId", section), token);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            // Nothing of the half-made family survives the refusal.
            assertThat(count("SELECT count(*) FROM student WHERE first_name = ?", marker)).isZero();
            assertThat(count("SELECT count(*) FROM guardian WHERE phone = ?", phone)).isZero();
            assertThat(count("SELECT count(*) FROM user_account WHERE phone = ?", phone)).isZero();
            assertThat(count("SELECT count(*) FROM enrolment WHERE section_id = ?", section))
                .isEqualTo(seatsBefore);
            assertThat(queryOne("SELECT state FROM admission_application WHERE id = ?", String.class,
                applicationId)).isEqualTo("accepted");
            assertThat(count("SELECT count(*) FROM admission_application WHERE id = ? "
                + "AND converted_student_id IS NOT NULL", applicationId)).isZero();
            assertThat(count("SELECT count(*) FROM admission_event WHERE application_id = ? "
                + "AND to_state = 'enrolled'", applicationId)).isZero();

            // And the application is still confirmable once the obstacle is gone.
            dropAdm10Trigger();
            var confirmed = post("/v1/admissions/applications/" + applicationId + "/enrol",
                Map.of("sectionId", section), token);
            assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
            UUID studentId = UUID.fromString(confirmed.getBody().get("studentId").asText());

            // The student is the applicant, numbered by the school rather than by
            // the application.
            assertThat(queryOne("SELECT first_name || ' ' || last_name FROM student WHERE id = ?",
                String.class, studentId)).isEqualTo(marker + " Kulkarni");
            assertThat(queryOne("SELECT admission_no FROM student WHERE id = ?", String.class, studentId))
                .isNotBlank().doesNotStartWith("ADM10-");

            // One open enrolment, in the section the seat was confirmed for.
            assertThat(queryList("SELECT section_id FROM enrolment WHERE student_id = ? AND ends_on IS NULL",
                UUID.class, studentId)).containsExactly(section);
            assertThat(queryOne("SELECT academic_year_id FROM enrolment WHERE student_id = ?", UUID.class,
                studentId)).isEqualTo(cbse().currentAy().id());

            // The application points at the student it became.
            assertThat(queryOne("SELECT state FROM admission_application WHERE id = ?", String.class,
                applicationId)).isEqualTo("enrolled");
            assertThat(queryOne("SELECT converted_student_id FROM admission_application WHERE id = ?",
                UUID.class, applicationId)).isEqualTo(studentId);

            // The guardian who applied is this child's primary guardian...
            UUID guardianId = queryOne("SELECT id FROM guardian WHERE phone = ? AND school_id = ?",
                UUID.class, phone, cbse().id());
            assertThat(count("SELECT count(*) FROM guardian_student WHERE guardian_id = ? AND student_id = ? "
                + "AND is_primary", guardianId, studentId)).isEqualTo(1);

            // ...and can walk in through the ordinary door and find them.
            post("/v1/auth/otp/start", Map.of("identifier", phone, "chainSlug", seed.chainSlug()), null);
            var signedIn = post("/v1/auth/otp/verify",
                Map.of("identifier", phone, "chainSlug", seed.chainSlug(), "code", "000000"), null);
            assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(signedIn.getBody().get("profile").get("subjectType").asText()).isEqualTo("guardian");
            var children = get("/v1/people/guardians/" + guardianId + "/students",
                signedIn.getBody().get("accessToken").asText());
            assertThat(children.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(children.getBody().findValuesAsText("id")).containsExactly(studentId.toString());

            // Confirming the same seat again answers with the same student and
            // makes nothing new.
            var again = post("/v1/admissions/applications/" + applicationId + "/enrol",
                Map.of("sectionId", section), token);
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(again.getBody().get("studentId").asText()).isEqualTo(studentId.toString());
            assertThat(count("SELECT count(*) FROM student WHERE first_name = ?", marker)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM guardian WHERE phone = ?", phone)).isEqualTo(1);
        } finally {
            dropAdm10Trigger();
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM user_account WHERE phone = ?", phone);
                jdbc.update("DELETE FROM guardian_student WHERE student_id IN "
                    + "(SELECT id FROM student WHERE first_name = ?)", marker);
                jdbc.update("DELETE FROM guardian WHERE phone = ?", phone);
                jdbc.update("DELETE FROM enrolment WHERE student_id IN "
                    + "(SELECT id FROM student WHERE first_name = ?)", marker);
                jdbc.update("UPDATE admission_application SET converted_student_id = NULL WHERE id = ?",
                    applicationId);
                jdbc.update("DELETE FROM student WHERE first_name = ?", marker);
                jdbc.update("DELETE FROM admission_event WHERE application_id = ?", applicationId);
                jdbc.update("DELETE FROM admission_application WHERE id = ?", applicationId);
            });
        }
    }

    private void dropAdm10Trigger() {
        inChainDo(jdbc -> {
            jdbc.execute("DROP TRIGGER IF EXISTS adm10_refuse_link ON guardian_student");
            jdbc.execute("DROP FUNCTION IF EXISTS adm10_refuse_link()");
        });
    }

    @Test @Tag("P1")
    void cert_ADM_11_siblingAdmissionLinksTheFamilyAndAppliesTheConcession() {
        String token = registrarToken(cbse());
        String accountant = accountantToken(cbse());

        // An existing family: take a student and the guardian who already has
        // their login.
        UUID elder = firstStudentIn(currentFocusSection(cbse()));
        UUID guardianId = queryOne(
            "SELECT guardian_id FROM guardian_student WHERE student_id = ? ORDER BY is_primary DESC LIMIT 1",
            UUID.class, elder);
        String guardianPhone = queryOne("SELECT phone FROM guardian WHERE id = ?", String.class, guardianId);
        UUID youngerSection = sectionOf(cbse(), cbse().currentAy().code(), "6", "A");
        UUID youngerGrade = gradeOf(cbse(), "6");

        post("/v1/fees/sibling-policies", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
            "nthChild", 2, "pct", 25.0), accountant);

        // The younger sibling applies, with the same guardian phone.
        UUID applicationId = UUID.fromString(post("/v1/admissions/applications", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(), "gradeId", youngerGrade,
            "applicationNo", "ADM11-" + UUID.randomUUID().toString().substring(0, 8),
            "applicantFirstName", "Younger", "applicantLastName", "Sibling",
            "applicantDob", "2016-02-02", "applicantGender", "female",
            "guardianName", "Sibling Guardian", "guardianPhone", guardianPhone,
            "source", "walkin"), token).getBody().get("id").asText());
        for (String state : List.of("application_started", "document_pending", "fee_pending", "review",
                "test_scheduled", "test_done", "offered", "accepted")) {
            post("/v1/admissions/applications/" + applicationId + "/transition",
                Map.of("toState", state), token);
        }
        UUID younger = UUID.fromString(post("/v1/admissions/applications/" + applicationId + "/enrol",
            Map.of("sectionId", youngerSection), token).getBody().get("studentId").asText());

        try {
            // The guardian's existing login now covers both children.
            var children = get("/v1/people/guardians/" + guardianId + "/students",
                guardianTokenFor(cbse(), elder)).getBody();
            List<String> ids = children.findValuesAsText("id");
            assertThat(ids).contains(elder.toString(), younger.toString());

            // Linking the household applies the sibling rule to the new child's bill.
            UUID familyId = UUID.fromString(post("/v1/fees/families/link",
                body("schoolId", cbse().id(), "studentId", younger), accountant)
                .getBody().get("familyId").asText());
            assertThat(queryOne("SELECT family_id FROM student WHERE id = ?", UUID.class, elder))
                .isEqualTo(familyId);

            post("/v1/fees/generate", body(
                "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
                "gradeId", youngerGrade, "cycleLabel", "ADM11 cycle", "dueOn", "2026-09-10"), accountant);

            double discount = queryOne(
                "SELECT COALESCE(sum(l.discount), 0) FROM fee_invoice_line l " +
                "JOIN fee_invoice i ON i.id = l.fee_invoice_id " +
                "WHERE i.student_id = ? AND i.cycle_label = 'ADM11 cycle'", Double.class, younger);
            assertThat(discount).isGreaterThan(0);
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM fee_invoice_line WHERE fee_invoice_id IN " +
                    "(SELECT id FROM fee_invoice WHERE cycle_label = 'ADM11 cycle')");
                jdbc.update("DELETE FROM fee_invoice WHERE cycle_label = 'ADM11 cycle'");
                jdbc.update("DELETE FROM fee_schedule_run WHERE cycle_label = 'ADM11 cycle'");
                jdbc.update("DELETE FROM sibling_concession_policy WHERE school_id = ?", cbse().id());
                jdbc.update("UPDATE student SET family_id = NULL WHERE family_id IN " +
                    "(SELECT id FROM family WHERE school_id = ?)", cbse().id());
                jdbc.update("DELETE FROM family WHERE school_id = ?", cbse().id());
                jdbc.update("DELETE FROM guardian_student WHERE student_id = ?", younger);
                jdbc.update("DELETE FROM enrolment WHERE student_id = ?", younger);
                jdbc.update("UPDATE admission_application SET converted_student_id = NULL WHERE id = ?",
                    applicationId);
                jdbc.update("DELETE FROM student WHERE id = ?", younger);
                jdbc.update("DELETE FROM admission_event WHERE application_id = ?", applicationId);
                jdbc.update("DELETE FROM admission_application WHERE id = ?", applicationId);
            });
        }
    }

    @Test @Tag("P1")
    @Disabled("GAP-16 — admission documents are loose JSONB with no per-document verification state or "
        + "reviewer, and enrolment is not gated on them (Phase 8).")
    void cert_ADM_12_mandatoryDocumentsAreVerifiedBeforeEnrolment() {
    }

    @Test @Tag("P1")
    void cert_ADM_13_admissionFeeFailureLeavesTheApplicationInFeePending() {
        String registrar = registrarToken(cbse());
        String accountant = accountantToken(cbse());
        UUID gradeId = gradeOf(cbse(), "3");
        String marker = "Adm13" + UUID.randomUUID().toString().substring(0, 6);
        String phone = "919444" + (100000 + (int) (Math.random() * 800000));

        // The school prices admission to Grade 3, and to no other grade.
        var priced = put("/v1/admissions/fees", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
            "gradeId", gradeId, "amount", 2500.0), registrar);
        assertThat(priced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(priced.getBody()).hasSize(1);
        assertThat(priced.getBody().get(0).get("amount").asDouble()).isEqualTo(2500.0);

        UUID applicationId = UUID.fromString(post("/v1/admissions/applications", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(), "gradeId", gradeId,
            "applicationNo", "ADM13-" + UUID.randomUUID().toString().substring(0, 8),
            "applicantFirstName", marker, "applicantLastName", "Desai",
            "applicantDob", "2018-03-14", "applicantGender", "male",
            "guardianName", "Kiran Desai", "guardianPhone", phone,
            "source", "walkin"), registrar).getBody().get("id").asText());
        String move = "/v1/admissions/applications/" + applicationId + "/transition";
        String fee = "/v1/admissions/applications/" + applicationId + "/fee";

        try {
            // Nothing is owed until the application reaches the stage.
            post(move, Map.of("toState", "application_started"), registrar);
            post(move, Map.of("toState", "document_pending"), registrar);
            assertThat(get(fee, registrar).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            // Reaching fee_pending bills the applicant — who is not a student yet.
            post(move, Map.of("toState", "fee_pending"), registrar);
            var billed = get(fee, registrar);
            assertThat(billed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(billed.getBody().get("total").asDouble()).isEqualTo(2500.0);
            assertThat(billed.getBody().get("paid").asDouble()).isZero();
            UUID invoiceId = UUID.fromString(billed.getBody().get("invoiceId").asText());
            assertThat(count("SELECT count(*) FROM fee_invoice WHERE id = ? AND student_id IS NULL "
                + "AND admission_application_id = ?", invoiceId, applicationId)).isEqualTo(1);

            // Unpaid, the application does not move on, and the refusal says why.
            var unpaid = post(move, Map.of("toState", "review"), registrar);
            assertThat(unpaid.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(unpaid.getBody().toString()).contains("admission fee").contains("2500");
            assertThat(stateOf(applicationId)).isEqualTo("fee_pending");

            // A cheque is taken, and comes back unpaid. The payment is kept as
            // failed, the fee is owed again, and the application has not moved.
            UUID cheque = UUID.fromString(post("/v1/fees/payments", body(
                "schoolId", cbse().id(), "feeInvoiceId", invoiceId, "amount", 2500.0,
                "gateway", "cheque", "method", "cheque", "idempotencyKey", "adm13-cheque-" + invoiceId),
                accountant).getBody().get("id").asText());
            assertThat(get(fee, registrar).getBody().get("status").asText()).isEqualTo("paid");
            assertThat(post("/v1/fees/invoices/" + invoiceId + "/adjustments", body(
                "schoolId", cbse().id(), "kind", "reversal", "amount", 2500.0,
                "reason", "Cheque returned unpaid", "paymentId", cheque,
                "approvedByStaffId", cbse().accountantStaffId()), accountant).getStatusCode())
                .isEqualTo(HttpStatus.OK);
            assertThat(queryOne("SELECT status FROM payment WHERE id = ?", String.class, cheque))
                .isEqualTo("failed");
            assertThat(stateOf(applicationId)).isEqualTo("fee_pending");
            assertThat(post(move, Map.of("toState", "review"), registrar).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

            // Part of it is not all of it.
            post("/v1/fees/payments", body(
                "schoolId", cbse().id(), "feeInvoiceId", invoiceId, "amount", 1000.0,
                "gateway", "cash", "method", "cash", "idempotencyKey", "adm13-part-" + invoiceId), accountant);
            assertThat(post(move, Map.of("toState", "review"), registrar).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
            assertThat(stateOf(applicationId)).isEqualTo("fee_pending");

            // Stepping back for a missing document and returning finds the same
            // bill, with what was paid still on it.
            post(move, Map.of("toState", "document_pending"), registrar);
            post(move, Map.of("toState", "fee_pending"), registrar);
            assertThat(get(fee, registrar).getBody().get("invoiceId").asText()).isEqualTo(invoiceId.toString());
            assertThat(get(fee, registrar).getBody().get("paid").asDouble()).isEqualTo(1000.0);

            // The family pays the rest, and now the move is allowed.
            post("/v1/fees/payments", body(
                "schoolId", cbse().id(), "feeInvoiceId", invoiceId, "amount", 1500.0,
                "gateway", "cash", "method", "cash", "idempotencyKey", "adm13-rest-" + invoiceId), accountant);
            var reviewed = post(move, Map.of("toState", "review"), registrar);
            assertThat(reviewed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(reviewed.getBody().get("state").asText()).isEqualTo("review");

            // When the seat is confirmed, the fee is on the child's account.
            for (String state : List.of("test_scheduled", "test_done", "offered", "accepted")) {
                post(move, Map.of("toState", state), registrar);
            }
            UUID studentId = UUID.fromString(post("/v1/admissions/applications/" + applicationId + "/enrol",
                Map.of("sectionId", sectionOf(cbse(), cbse().currentAy().code(), "3", "A")), registrar)
                .getBody().get("studentId").asText());
            assertThat(queryOne("SELECT student_id FROM fee_invoice WHERE id = ?", UUID.class, invoiceId))
                .isEqualTo(studentId);
            assertThat(get("/v1/fees/invoices?studentId=" + studentId, accountant).getBody()
                .findValuesAsText("id")).contains(invoiceId.toString());

            // A grade the school does not price passes through with nothing to pay.
            var free = createApplication("walkin", "ADM13F-" + UUID.randomUUID().toString().substring(0, 8));
            UUID freeId = UUID.fromString(free.getBody().get("id").asText());
            for (String state : List.of("application_started", "document_pending", "fee_pending")) {
                post("/v1/admissions/applications/" + freeId + "/transition", Map.of("toState", state), registrar);
            }
            assertThat(get("/v1/admissions/applications/" + freeId + "/fee", registrar).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(post("/v1/admissions/applications/" + freeId + "/transition",
                Map.of("toState", "review"), registrar).getStatusCode()).isEqualTo(HttpStatus.OK);

            // Taking the price away takes the row away.
            assertThat(put("/v1/admissions/fees", body(
                "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
                "gradeId", gradeId, "amount", 0.0), registrar).getBody()).isEmpty();
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM admission_fee WHERE school_id = ?", cbse().id());
                jdbc.update("DELETE FROM ledger_entry WHERE source_id IN (SELECT id FROM payment WHERE "
                    + "fee_invoice_id IN (SELECT id FROM fee_invoice WHERE admission_application_id = ?))",
                    applicationId);
                jdbc.update("DELETE FROM ledger_entry WHERE source_id IN (SELECT id FROM fee_adjustment WHERE "
                    + "fee_invoice_id IN (SELECT id FROM fee_invoice WHERE admission_application_id = ?))",
                    applicationId);
                jdbc.update("DELETE FROM fee_adjustment WHERE fee_invoice_id IN "
                    + "(SELECT id FROM fee_invoice WHERE admission_application_id = ?)", applicationId);
                jdbc.update("DELETE FROM payment WHERE fee_invoice_id IN "
                    + "(SELECT id FROM fee_invoice WHERE admission_application_id = ?)", applicationId);
                jdbc.update("DELETE FROM fee_invoice_line WHERE fee_invoice_id IN "
                    + "(SELECT id FROM fee_invoice WHERE admission_application_id = ?)", applicationId);
                jdbc.update("DELETE FROM fee_invoice WHERE admission_application_id = ?", applicationId);
                jdbc.update("DELETE FROM fee_head h WHERE h.school_id = ? AND h.code = 'ADMISSION' AND NOT EXISTS "
                + "(SELECT 1 FROM fee_invoice_line l WHERE l.fee_head_id = h.id)", cbse().id());
                jdbc.update("DELETE FROM user_account WHERE phone = ?", phone);
                jdbc.update("DELETE FROM guardian_student WHERE student_id IN "
                    + "(SELECT id FROM student WHERE first_name = ?)", marker);
                jdbc.update("DELETE FROM guardian WHERE phone = ?", phone);
                jdbc.update("DELETE FROM enrolment WHERE student_id IN "
                    + "(SELECT id FROM student WHERE first_name = ?)", marker);
                jdbc.update("UPDATE admission_application SET converted_student_id = NULL WHERE id = ?",
                    applicationId);
                jdbc.update("DELETE FROM student WHERE first_name = ?", marker);
                jdbc.update("DELETE FROM admission_event WHERE application_id = ?", applicationId);
                jdbc.update("DELETE FROM admission_application WHERE id = ?", applicationId);
            });
        }
    }

    @Test @Tag("P2")
    void cert_ADM_14_rejectedApplicantIsNotifiedAndStaysOffRosters() {
        String token = registrarToken(cbse());
        var application = createApplication("walkin", "REJ-" + UUID.randomUUID().toString().substring(0, 8));
        UUID id = UUID.fromString(application.getBody().get("id").asText());

        var rejected = post("/v1/admissions/applications/" + id + "/transition",
            Map.of("toState", "rejected"), token);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getBody().get("state").asText()).isEqualTo("rejected");

        // Told, on the channels the form gave the school.
        assertThat(count("SELECT count(*) FROM notification_dispatch WHERE recipient_type = 'applicant' "
            + "AND recipient_id = ? AND template_code = 'admission_rejected'", id)).isEqualTo(2);

        // Re-running a transition that already happened is not an error, and it
        // is not a second rejection letter either.
        var again = post("/v1/admissions/applications/" + id + "/transition",
            Map.of("toState", "rejected"), token);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM notification_dispatch WHERE recipient_id = ? "
            + "AND template_code = 'admission_rejected'", id)).isEqualTo(2);

        // Retained, and nowhere near a roster: the applicant never became a
        // student, so no enrolment, no section, no register.
        assertThat(queryOne("SELECT state FROM admission_application WHERE id = ?", String.class, id))
            .isEqualTo("rejected");
        assertThat(count("SELECT count(*) FROM admission_application WHERE id = ? "
            + "AND converted_student_id IS NULL", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM student s JOIN admission_application a "
            + "ON a.converted_student_id = s.id WHERE a.id = ?", id)).isZero();

        // The trail of the decision survives the rejection.
        var events = get("/v1/admissions/applications/" + id + "/events", token).getBody();
        List<String> states = new ArrayList<>();
        events.forEach(node -> states.add(node.get("toState").asText()));
        assertThat(states).contains("rejected");
    }

    @Test @Tag("P2")
    @Disabled("No funnel analytics endpoint: conversion by source/stage, drop-off and time-in-stage are "
        + "not computed anywhere. New gap found in Phase 0.")
    void cert_ADM_15_funnelAnalyticsReconcileWithTheRawApplicationList() {
    }

    @Test @Tag("P1")
    void cert_ADM_16_midYearAdmissionProRatesFeesAndAttendance() {
        String principal = principalToken(cbse());
        String accountant = accountantToken(cbse());
        String cycle = "ADM16 cycle";
        String tag = "ADM16-" + UUID.randomUUID().toString().substring(0, 6);
        UUID gradeId = gradeOf(cbse(), "6");
        UUID sectionA = sectionOf(cbse(), cbse().currentAy().code(), "6", "A");
        UUID sectionB = sectionOf(cbse(), cbse().currentAy().code(), "6", "B");
        UUID classmate = firstStudentIn(sectionA);

        // Joined on 5 October, into a cycle that runs July to December.
        UUID joiner = newStudent(tag + "-JOIN");
        assertThat(post("/v1/enrolment", body(
            "schoolId", cbse().id(), "studentId", joiner, "sectionId", sectionB,
            "academicYearId", cbse().currentAy().id(), "startsOn", "2026-10-05"), principal)
            .getStatusCode()).isEqualTo(HttpStatus.OK);

        // There since April, and moved from A to B on 1 October. Their enrolment
        // in B is as new as the joiner's; they are not.
        UUID mover = newStudent(tag + "-MOVE");
        inChainDo(jdbc -> {
            jdbc.update("INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, "
                + "starts_on, ends_on, status) VALUES (gen_random_uuid(), ?, ?, ?, ?, '2026-04-01', "
                + "'2026-09-30', 'transferred')", cbse().id(), mover, sectionA, cbse().currentAy().id());
            jdbc.update("INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, "
                + "starts_on, status) VALUES (gen_random_uuid(), ?, ?, ?, ?, '2026-10-01', 'active')",
                cbse().id(), mover, sectionB, cbse().currentAy().id());
        });

        // A one-time head on the grade's structure, beside the recurring ones.
        UUID onceHead = UUID.randomUUID();
        inChainDo(jdbc -> {
            jdbc.update("INSERT INTO fee_head (id, school_id, code, name, is_recurring, gst_rate_pct) "
                + "VALUES (?, ?, ?, 'Annual kit', FALSE, 0)", onceHead, cbse().id(), tag);
            jdbc.update("INSERT INTO fee_structure_line (id, fee_structure_id, fee_head_id, amount) "
                + "SELECT gen_random_uuid(), id, ?, 4000 FROM fee_structure "
                + "WHERE school_id = ? AND grade_id = ? AND academic_year_id = ?",
                onceHead, cbse().id(), gradeId, cbse().currentAy().id());
        });

        try {
            var run = post("/v1/fees/generate", body(
                "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(), "gradeId", gradeId,
                "cycleLabel", cycle, "dueOn", "2026-10-20",
                "periodStart", "2026-07-01", "periodEnd", "2026-12-31"), accountant);
            assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);

            // Three of the six months: the recurring heads are halved, and the
            // line says why.
            assertThat(billed(classmate, cycle, "TUITION")).isEqualTo(30000.0);
            assertThat(billed(joiner, cycle, "TUITION")).isEqualTo(15000.0);
            assertThat(billed(joiner, cycle, "LAB")).isEqualTo(1000.0);
            assertThat(queryOne("SELECT l.description FROM fee_invoice_line l "
                + "JOIN fee_invoice i ON i.id = l.fee_invoice_id JOIN fee_head h ON h.id = l.fee_head_id "
                + "WHERE i.student_id = ? AND i.cycle_label = ? AND h.code = 'TUITION'",
                String.class, joiner, cycle)).contains("3 of 6 months");

            // A one-time head is owed whole by whoever is billed it.
            assertThat(billed(joiner, cycle, tag)).isEqualTo(4000.0);

            // And the invoice adds up to its lines.
            assertThat(queryOne("SELECT total FROM fee_invoice WHERE student_id = ? AND cycle_label = ?",
                Double.class, joiner, cycle)).isEqualTo(15000.0 + 1000.0 + 750.0 + 4000.0);

            // Changing section is not joining late.
            assertThat(billed(mover, cycle, "TUITION")).isEqualTo(30000.0);

            // The period is on the run, where "what did October's run cover" is asked.
            boolean listed = false;
            for (var row : get("/v1/fees/runs?schoolId=" + cbse().id() + "&academicYearId="
                    + cbse().currentAy().id(), accountant).getBody()) {
                if (cycle.equals(row.get("cycleLabel").asText())) {
                    assertThat(row.get("periodStart").asText()).isEqualTo("2026-07-01");
                    assertThat(row.get("periodEnd").asText()).isEqualTo("2026-12-31");
                    listed = true;
                }
            }
            assertThat(listed).isTrue();

            // A cycle that was over before the child arrived bills them nothing.
            post("/v1/fees/generate", body(
                "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(), "gradeId", gradeId,
                "cycleLabel", cycle + " past", "dueOn", "2026-10-20",
                "periodStart", "2026-04-01", "periodEnd", "2026-06-30"), accountant);
            assertThat(count("SELECT count(*) FROM fee_invoice WHERE student_id = ? AND cycle_label = ?",
                joiner, cycle + " past")).isZero();
            assertThat(count("SELECT count(*) FROM fee_invoice WHERE student_id = ? AND cycle_label = ?",
                classmate, cycle + " past")).isEqualTo(1);

            // Attendance is measured from the day they joined: over the same
            // fortnight the joiner's denominator is the classmate's from the 5th.
            var joinerDays = get("/v1/attendance/students/" + joiner
                + "/summary?from=2026-09-21&to=2026-10-16", principal).getBody();
            assertThat(joinerDays.get("enrolledFrom").asText()).isEqualTo("2026-10-05");
            int fromTheFifth = get("/v1/attendance/students/" + classmate
                + "/summary?from=2026-10-05&to=2026-10-16", principal).getBody().get("workingDays").asInt();
            int wholeWindow = get("/v1/attendance/students/" + classmate
                + "/summary?from=2026-09-21&to=2026-10-16", principal).getBody().get("workingDays").asInt();
            assertThat(joinerDays.get("workingDays").asInt()).isEqualTo(fromTheFifth).isLessThan(wholeWindow);

            // And the report card carries that number, not the term's.
            UUID term2 = termOf(cbse(), cbse().currentAy().code(), "T2");
            UUID joinerCard = reportCard(joiner, term2, "CERT-ADM16");
            UUID classmateCard = reportCard(classmate, term2, "CERT-ADM16");
            int joinerCardDays = get("/v1/assessment/report-cards/" + joinerCard, principal).getBody()
                .get("card").get("attendanceWorkingDays").asInt();
            int classmateCardDays = get("/v1/assessment/report-cards/" + classmateCard, principal).getBody()
                .get("card").get("attendanceWorkingDays").asInt();
            assertThat(joinerCardDays).isPositive().isLessThan(classmateCardDays);
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM report_card WHERE template_code = 'CERT-ADM16'");
                jdbc.update("DELETE FROM fee_invoice_line WHERE fee_invoice_id IN "
                    + "(SELECT id FROM fee_invoice WHERE cycle_label LIKE 'ADM16 cycle%')");
                jdbc.update("DELETE FROM fee_invoice WHERE cycle_label LIKE 'ADM16 cycle%'");
                jdbc.update("DELETE FROM fee_schedule_run WHERE cycle_label LIKE 'ADM16 cycle%'");
                jdbc.update("DELETE FROM fee_structure_line WHERE fee_head_id = ?", onceHead);
                jdbc.update("DELETE FROM fee_head WHERE id = ?", onceHead);
                jdbc.update("DELETE FROM enrolment WHERE student_id IN (?, ?)", joiner, mover);
                jdbc.update("DELETE FROM student WHERE id IN (?, ?)", joiner, mover);
            });
        }
    }

    // ---------------------------------------------------------------- helpers

    private String stateOf(UUID applicationId) {
        return queryOne("SELECT state FROM admission_application WHERE id = ?", String.class, applicationId);
    }

    private UUID newStudent(String admissionNo) {
        var created = post("/v1/people/students", Map.of(
            "schoolId", cbse().id(), "admissionNo", admissionNo,
            "firstName", "Certification", "lastName", "Candidate",
            "dob", "2015-05-05", "gender", "male"), principalToken(cbse()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }

    /** What one head came to on a student's generated invoice for a cycle. */
    private double billed(UUID studentId, String cycle, String headCode) {
        return queryOne("SELECT l.amount FROM fee_invoice_line l "
            + "JOIN fee_invoice i ON i.id = l.fee_invoice_id JOIN fee_head h ON h.id = l.fee_head_id "
            + "WHERE i.student_id = ? AND i.cycle_label = ? AND h.code = ?",
            Double.class, studentId, cycle, headCode);
    }

    private UUID reportCard(UUID studentId, UUID termId, String templateCode) {
        var card = post("/v1/assessment/report-cards", body(
            "schoolId", cbse().id(), "studentId", studentId, "academicYearId", cbse().currentAy().id(),
            "termId", termId, "strategyCode", cbse().strategyCode(), "templateCode", templateCode),
            principalToken(cbse()));
        assertThat(card.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(card.getBody().get("id").asText());
    }

    private org.springframework.http.ResponseEntity<com.fasterxml.jackson.databind.JsonNode> createApplication(
            String source, String applicationNo) {
        return post("/v1/admissions/applications", body(
            "schoolId", cbse().id(),
            "academicYearId", cbse().currentAy().id(),
            "gradeId", gradeOf(cbse(), "1"),
            "applicationNo", applicationNo,
            "applicantFirstName", "Aarav",
            "applicantLastName", "Sharma",
            "applicantDob", "2020-06-15",
            "applicantGender", "male",
            "guardianName", "Rohit Sharma",
            "guardianPhone", "+919000" + (100000 + (int) (Math.random() * 800000)),
            "guardianEmail", "rohit." + UUID.randomUUID().toString().substring(0, 6) + "@example.test",
            "source", source), registrarToken(cbse()));
    }

    private Map<String, String> applyThroughPublicSite() {
        // Digits only: the tracking lookup round-trips the phone through a query parameter.
        String phone = "919111" + (100000 + (int) (Math.random() * 800000));
        var applied = post("/v1/public/schools/" + seed.chainSlug() + "/" + cbse().slug() + "/admissions/apply",
            body("applicantFirstName", "Ishita", "applicantLastName", "Verma",
                "applicantDob", "2020-03-02", "applicantGender", "female",
                "gradeId", gradeOf(cbse(), "1"), "guardianName", "Neha Verma",
                "guardianPhone", phone, "guardianEmail", "neha@example.test"), null);
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        String applicationNo = applied.getBody().get("applicationNo").asText();

        var tracked = get("/v1/public/schools/" + seed.chainSlug() + "/" + cbse().slug()
            + "/admissions/track?applicationNo=" + applicationNo + "&guardianPhone=" + phone, null);
        assertThat(tracked.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Map.of("applicationNo", applicationNo, "source", tracked.getBody().get("source").asText());
    }
}
