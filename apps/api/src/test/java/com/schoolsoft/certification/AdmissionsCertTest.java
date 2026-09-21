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
        UUID applicationId = UUID.fromString(tracked.getBody().get("id").asText());

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
    @Disabled("Conversion creates the student and the enrolment and links converted_student_id, but no "
        + "guardian is created or linked, so the family has no login after admission. New gap found in "
        + "Phase 0.")
    void cert_ADM_10_seatConfirmationCreatesStudentGuardianAndEnrolmentTransactionally() {
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
    @Disabled("An applicant has no student row until conversion and fee_invoice.student_id is NOT NULL, so "
        + "the admission fee cannot be invoiced at fee_pending. New gap found in Phase 0.")
    void cert_ADM_13_admissionFeeFailureLeavesTheApplicationInFeePending() {
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
    @Disabled("The working-day denominator (Phase 1) and the fee engine (Phase 4) both exist now, but "
        + "nothing pro-rates a cycle for a mid-year joiner: generation bills the full structure amount to "
        + "whoever is enrolled on the run date.")
    void cert_ADM_16_midYearAdmissionProRatesFeesAndAttendance() {
    }

    // ---------------------------------------------------------------- helpers

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
