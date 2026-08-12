package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-ASMT — assessment, marks & report cards. */
class AssessmentCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_ASMT_01_cbseAssessmentsAreCreatedWithComponentsWeightedToOneHundred() {
        String token = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());

        var assessment = post("/v1/assessment", body(
            "schoolId", cbse().id(), "sectionId", sectionId, "subjectId", subjectOf(cbse(), "MATH"),
            "termId", termOf(cbse(), cbse().currentAy().code(), "T2"),
            "strategyCode", "CBSE-CCE-2024", "name", "Half Yearly — Mathematics",
            "assessmentType", "HY", "maxMarks", 80.0, "weightPct", 30.0,
            "scheduledOn", "2026-09-20"), token);
        assertThat(assessment.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID assessmentId = UUID.fromString(assessment.getBody().get("id").asText());

        post("/v1/assessment/" + assessmentId + "/components",
            body("code", "THEORY", "name", "Theory paper", "maxMarks", 60.0, "weightPct", 75.0, "sortOrder", 1), token);
        post("/v1/assessment/" + assessmentId + "/components",
            body("code", "PRACT", "name", "Practical", "maxMarks", 20.0, "weightPct", 25.0, "sortOrder", 2), token);

        var components = get("/v1/assessment/" + assessmentId + "/components", token).getBody();
        assertThat(components).hasSize(2);
        double totalWeight = 0;
        double totalMarks = 0;
        for (var component : components) {
            totalWeight += component.get("weightPct").asDouble();
            totalMarks += component.get("maxMarks").asDouble();
        }
        assertThat(totalWeight).isEqualTo(100.0);
        assertThat(totalMarks).isEqualTo(assessment.getBody().get("maxMarks").asDouble());
    }

    @Test @Tag("P1")
    @Disabled("Cambridge assessments persist with their own strategy code, but no grade is computed at "
        + "all: mark.grade_letter is whatever the caller supplies, so a CIE scale cannot be applied "
        + "independently of CBSE. Needs the strategy grade computation of GAP-29 (Phase 5).")
    void cert_ASMT_02_cambridgeComponentsGradeOnTheCieScale() {
    }

    @Test @Tag("P1")
    @Disabled("No weight validation: components whose weights do not sum to the assessment total are "
        + "accepted silently, and nothing flags it before marking opens. New gap found in Phase 0.")
    void cert_ASMT_03_componentWeightsThatDoNotSumAreRejected() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-06 — there is no bulk mark endpoint, no max_marks validation on entry, and a blank "
        + "mark is indistinguishable from a zero (mark.status does not exist) (Phase 5).")
    void cert_ASMT_04_bulkMarkEntryValidatesMaximaAndBlanks() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-06 — no absent semantics on a mark, so an exam absence cannot be excluded from "
        + "averages or rendered as AB (Phase 5).")
    void cert_ASMT_05_examAbsenceIsRecordedAsAbsentNotZero() {
    }

    @Test @Tag("P1")
    @Disabled("The lifecycle statuses persist, but nothing enforces them: AssessmentRepository.enterMark "
        + "upserts a mark regardless of the assessment being `locked`. New gap found in Phase 0.")
    void cert_ASMT_06_marksAreNotEditableOnceLocked() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-06 + GAP-27 — unlocking is an unguarded status write with no role check, no reason and "
        + "no audit row (Phase 5).")
    void cert_ASMT_07_unlockRequiresAuthorisedRoleReasonAndAudit() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-06 — no mark_revision: a moderated mark overwrites the original rather than "
        + "superseding it, and nothing regenerates the report card (Phase 5).")
    void cert_ASMT_08_reEvaluationSupersedesWithoutDiscardingTheOriginal() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-06 — no exam_schedule/exam_session entity: no per-student paper clash check, no room "
        + "or invigilator allocation, no hall tickets (Phase 5).")
    void cert_ASMT_09_examTimetableAvoidsPerStudentClashesAndIssuesHallTickets() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-13 — report_card.payload is free-form JSON with no content model: no subject rows, "
        + "attendance summary, co-scholastic ratings, remarks or promotion decision (Phase 5).")
    void cert_ASMT_10_reportCardCarriesMarksAttendanceRemarksAndPromotionDecision() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-29 — no rank, percentile or grade-boundary computation (Phase 5).")
    void cert_ASMT_11_rankAndPercentileAreComputedReproducibly() {
    }

    @Test @Tag("P1")
    @Disabled("Locking a report card sets is_locked, but generateReportCard always inserts a new row, so "
        + "a locked card can be silently superseded by a regenerated one. New gap found in Phase 0.")
    void cert_ASMT_12_lockedReportCardCannotBeSilentlyRegenerated() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-05 — no student_subject election, so a report card cannot show a student's own "
        + "subject set (Phase 2).")
    void cert_ASMT_13_reportCardShowsTheStudentsOwnElectiveSet() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-13 — without a report-card content model there is nowhere to express 'terms attended' "
        + "or the explanatory note for a mid-year joiner (Phase 5).")
    void cert_ASMT_14_midYearJoinerReportCardShowsOnlyTermsAttended() {
    }

    @Test @Tag("P2")
    @Disabled("No dues-block policy: mark publication and fee status are unconnected, and the "
        + "withhold-vs-release choice is not configurable. New gap found in Phase 0.")
    void cert_ASMT_15_duesBlockPolicyIsHonouredAtPublication() {
    }
}
