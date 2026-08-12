package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-LMS — LMS, homework & content. */
class LmsCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_LMS_01_publishedAssignmentIsVisibleToTheSectionAndItsParents() {
        String teacher = teacherToken(cbse(), 0);
        UUID sectionId = currentFocusSection(cbse());
        UUID assignmentId = createAssignment(sectionId, "Fractions worksheet", "2026-08-20T18:00:00Z");

        var teacherView = get("/v1/lms/assignments?sectionId=" + sectionId, teacher).getBody();
        assertThat(idsOf(teacherView)).contains(assignmentId.toString());

        UUID studentId = firstStudentIn(sectionId);
        var parentView = get("/v1/lms/assignments?sectionId=" + sectionId,
            guardianTokenFor(cbse(), studentId)).getBody();
        assertThat(idsOf(parentView)).contains(assignmentId.toString());
    }

    @Test @Tag("P1")
    @Disabled("Submissions record a timestamp but nothing compares it to the assignment's due date: "
        + "assignment_submission has no late flag and no late policy. New gap found in Phase 0.")
    void cert_LMS_02_lateSubmissionIsAcceptedAndFlagged() {
    }

    @Test @Tag("P1")
    void cert_LMS_03_gradedSubmissionWithFeedbackReachesStudentAndParent() {
        String teacher = teacherToken(cbse(), 0);
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = firstStudentIn(sectionId);
        UUID assignmentId = createAssignment(sectionId, "Graded worksheet", "2026-08-25T18:00:00Z");

        var submitted = post("/v1/lms/assignments/" + assignmentId + "/submissions",
            Map.of("studentId", studentId, "body", "My answers"), teacher);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID submissionId = UUID.fromString(submitted.getBody().get("id").asText());

        var graded = post("/v1/lms/submissions/" + submissionId + "/grade",
            Map.of("marks", 17.5, "feedback", "Neat working; check Q4.",
                "gradedByStaffId", cbse().teacherStaffIds().get(0)), teacher);
        assertThat(graded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(graded.getBody().get("marks").asDouble()).isEqualTo(17.5);

        var parentView = get("/v1/lms/assignments/" + assignmentId + "/submissions",
            guardianTokenFor(cbse(), studentId)).getBody();
        assertThat(parentView).isNotEmpty();
        assertThat(parentView.get(0).get("feedback").asText()).isEqualTo("Neat working; check Q4.");
    }

    @Test @Tag("P2")
    @Disabled("Quiz attempts are stored, but the score is supplied by the caller: no auto-scoring against "
        + "the stored answers and no reattempt policy. New gap found in Phase 0 (authoring UI is already "
        + "in the backlog).")
    void cert_LMS_04_quizIsAutoScoredAndReattemptPolicyEnforced() {
    }

    @Test @Tag("P2")
    void cert_LMS_05_lessonPlanMovesThroughItsWorkflowAndIsVisibleToTheHead() {
        String teacher = teacherToken(cbse(), 0);
        UUID sectionId = currentFocusSection(cbse());

        var plan = post("/v1/lms/lesson-plans", body(
            "schoolId", cbse().id(), "sectionId", sectionId, "subjectId", subjectOf(cbse(), "MATH"),
            "title", "Fractions — lesson 3", "plannedFor", "2026-08-18", "durationMinutes", 45,
            "createdByStaffId", cbse().teacherStaffIds().get(0)), teacher);
        assertThat(plan.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID planId = UUID.fromString(plan.getBody().get("id").asText());
        assertThat(plan.getBody().get("status").asText()).isEqualTo("draft");

        var approved = post("/v1/lms/lesson-plans/" + planId + "/status", Map.of("status", "approved"), teacher);
        assertThat(approved.getBody().get("status").asText()).isEqualTo("approved");

        var headView = get("/v1/lms/lesson-plans?sectionId=" + sectionId, principalToken(cbse())).getBody();
        assertThat(idsOf(headView)).contains(planId.toString());
    }

    @Test @Tag("P1")
    void cert_LMS_06_uploadAndDownloadTicketsAreTenantScopedAndTimeBound() {
        String token = teacherToken(cbse(), 0);
        var ticket = post("/v1/files/upload-ticket",
            Map.of("filename", "worksheet.pdf", "mimeType", "application/pdf", "sizeBytes", 1024), token);
        assertThat(ticket.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID fileId = UUID.fromString(ticket.getBody().get("fileId").asText());
        assertThat(ticket.getBody().get("expiresAt").asText()).isNotBlank();

        var download = get("/v1/files/" + fileId + "/download-ticket", token);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(download.getBody().get("expiresAt").asText()).isNotBlank();

        // The other school in the same chain cannot resolve the same file id.
        var crossTenant = get("/v1/files/" + fileId + "/download-ticket", principalToken(cie()));
        assertThat(crossTenant.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @Tag("P3")
    @Disabled("LTI launch and grade passback are stubbed; no adapter exists to certify against "
        + "(already in the backlog).")
    void cert_LMS_07_ltiLaunchCarriesRolesAndReturnsAGrade() {
    }

    @Test @Tag("P1")
    void cert_LMS_08_assignmentIsInvisibleToOtherSectionsAndSchools() {
        String teacher = teacherToken(cbse(), 0);
        UUID sectionId = currentFocusSection(cbse());
        UUID otherSection = sectionOf(cbse(), cbse().currentAy().code(), cbse().focusGradeCode(), "B");
        UUID assignmentId = createAssignment(sectionId, "Section-scoped worksheet", "2026-08-28T18:00:00Z");

        assertThat(idsOf(get("/v1/lms/assignments?sectionId=" + otherSection, teacher).getBody()))
            .doesNotContain(assignmentId.toString());

        // Another school's staff sees nothing for that section id at all.
        assertThat(get("/v1/lms/assignments?sectionId=" + sectionId, principalToken(cie())).getBody()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private UUID createAssignment(UUID sectionId, String title, String dueAt) {
        var created = post("/v1/lms/assignments", body(
            "schoolId", cbse().id(), "sectionId", sectionId, "subjectId", subjectOf(cbse(), "MATH"),
            "title", title, "instructions", "See attachment", "submissionType", "text",
            "dueAt", dueAt, "maxMarks", 20.0,
            "createdByStaffId", cbse().teacherStaffIds().get(0)), teacherToken(cbse(), 0));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }

    private java.util.List<String> idsOf(com.fasterxml.jackson.databind.JsonNode array) {
        var ids = new java.util.ArrayList<String>();
        array.forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }
}
