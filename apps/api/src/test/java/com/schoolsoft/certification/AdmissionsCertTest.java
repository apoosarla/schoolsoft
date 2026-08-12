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
    @Disabled("Application is created correctly with source 'website', but nothing acknowledges it: no "
        + "module publishes a domain event or calls NotificationService, so no dispatch row is written. "
        + "New gap found in Phase 0 — notification producers are unwired.")
    void cert_ADM_01_publicEnquiryCreatesLeadAndAcknowledgesTheGuardian() {
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
    @Disabled("No server-side admissions state machine: AdmissionsRepository.transition writes any target "
        + "state, so `lead → enrolled` is accepted. New gap found in Phase 0.")
    void cert_ADM_05_invalidTransitionIsRejectedByTheServer() {
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
    @Disabled("Rejection transition works, but the guardian notification has no producer (see ADM-01). "
        + "New gap found in Phase 0.")
    void cert_ADM_14_rejectedApplicantIsNotifiedAndStaysOffRosters() {
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
