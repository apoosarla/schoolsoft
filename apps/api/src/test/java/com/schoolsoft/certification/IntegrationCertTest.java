package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-INT — board & external integration. */
class IntegrationCertTest extends AbstractCertificationTest {

    @Test @Tag("P2")
    @Disabled("A job can be enqueued and processed to 'completed' against the stub adapter, but a failed "
        + "job is terminal: process() accepts only status 'queued' and nothing re-queues, so the "
        + "retry-without-duplication half cannot pass. New gap found in Phase 0.")
    void cert_INT_01_exportJobIsProcessedAndRetryableWithoutDuplication() {
    }

    @Test @Tag("P2")
    void cert_INT_02_exportPayloadValidatesForACohortIncludingElectives() {
        String token = principalToken(cie());
        var block = createElectiveBlock(cie(), "INT02");
        UUID jobId = null;
        try {
            var job = post("/v1/board-integration/exports", body(
                "schoolId", cie().id(), "boardCode", "CIE", "exportType", "cie_candidate_registration",
                "academicYearId", cie().currentAy().id(), "sectionId", block.sectionId()), token);
            assertThat(job.getStatusCode()).isEqualTo(HttpStatus.OK);
            jobId = UUID.fromString(job.getBody().get("id").asText());

            String payload = queryOne("SELECT request_payload::text FROM board_export_job WHERE id = ?",
                String.class, jobId);
            // Every candidate in the cohort is present, each with their own
            // option — not the section's union of subjects.
            assertThat(payload).contains("INT02-A").contains("INT02-B");
            assertThat(candidateSubjects(payload, block.studentA())).contains("INT02-A")
                .doesNotContain("INT02-B");
            assertThat(candidateSubjects(payload, block.studentB())).contains("INT02-B")
                .doesNotContain("INT02-A");

            var processed = post("/v1/board-integration/exports/" + jobId + "/process", null, token);
            assertThat(processed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(processed.getBody().get("status").asText()).isEqualTo("completed");

            // A cohort with an incomplete candidate is refused by the schema
            // check rather than sent to the board.
            UUID incomplete = UUID.fromString(post("/v1/people/students", body(
                "schoolId", cie().id(), "firstName", "Nodob", "lastName", "Candidate",
                "gender", "male"), token).getBody().get("id").asText());
            post("/v1/enrolment", body(
                "schoolId", cie().id(), "studentId", incomplete, "sectionId", block.sectionId(),
                "academicYearId", cie().currentAy().id(), "startsOn", "2026-08-01"), token);

            UUID badJobId = UUID.fromString(post("/v1/board-integration/exports", body(
                "schoolId", cie().id(), "boardCode", "CIE", "exportType", "cie_candidate_registration",
                "academicYearId", cie().currentAy().id(), "sectionId", block.sectionId()), token)
                .getBody().get("id").asText());
            var failed = post("/v1/board-integration/exports/" + badJobId + "/process", null, token);
            assertThat(failed.getBody().get("status").asText()).isEqualTo("failed");
            assertThat(failed.getBody().get("errorMessage").asText()).contains("missing dob");

            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM enrolment WHERE student_id = ?", incomplete);
                jdbc.update("DELETE FROM student WHERE id = ?", incomplete);
                jdbc.update("DELETE FROM board_export_job WHERE id = ?", badJobId);
            });
        } finally {
            UUID created = jobId;
            inChainDo(jdbc -> {
                if (created != null) jdbc.update("DELETE FROM board_export_job WHERE id = ?", created);
            });
            deleteElectiveBlock(block);
        }
    }

    /** The subject codes attached to one candidate inside an export payload. */
    private String candidateSubjects(String payload, UUID studentId) {
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            for (var candidate : root.get("candidates")) {
                if (studentId.toString().equals(candidate.get("studentId").asText())) {
                    return candidate.get("subjects").toString();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable export payload", e);
        }
        throw new AssertionError("Student " + studentId + " missing from the export payload");
    }

    @Test @Tag("P3")
    @Disabled("UDISE+/CIE Direct adapters are stubs pending credentials (already in the backlog).")
    void cert_INT_03_submissionRetriesTransientFailuresWithoutResubmitting() {
    }

    @Test @Tag("P3")
    @Disabled("No accounting export (Tally/Zoho) exists (already in the backlog).")
    void cert_INT_04_accountingExportReconcilesToTheLedger() {
    }
}
