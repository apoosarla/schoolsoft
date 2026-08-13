package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-GRAD — graduation & alumni. */
class GraduationCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_GRAD_01_terminalGradeCohortGraduatesAtYearEnd() {
        var sandbox = rolloverSandbox("grad01");
        String token = sandboxToken(sandbox);
        try {
            UUID runId = roll(sandbox, token);
            var committed = post("/v1/rollover/runs/" + runId + "/commit", body(), token);
            assertThat(committed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(committed.getBody().get("graduated").asInt()).isEqualTo(10);

            // The top grade's enrolments close as graduated, and nothing opens
            // in the new year: there is no grade above to open it in.
            for (UUID student : sandbox.students("R3", "A")) {
                assertThat(queryOne("SELECT status FROM enrolment WHERE student_id = ? AND academic_year_id = ?",
                    String.class, student, sandbox.sourceAyId())).isEqualTo("graduated");
                assertThat(count("SELECT count(*) FROM enrolment WHERE student_id = ? AND status = 'active'",
                    student)).isZero();
            }

            // They are off every roster, which is what a roster is read for.
            assertThat(count(
                "SELECT count(*) FROM enrolment e JOIN section s ON s.id = e.section_id " +
                "WHERE e.academic_year_id = ? AND e.status = 'active' " +
                "  AND e.student_id IN (SELECT student_id FROM enrolment WHERE section_id = ?)",
                sandbox.targetAyId(), sandbox.section("R3", "A"))).isZero();

            // The student record itself stays: a graduate still has a history.
            assertThat(count("SELECT count(*) FROM student WHERE id = ?",
                sandbox.firstStudent("R3", "A"))).isEqualTo(1);
        } finally {
            dropSandbox(sandbox);
        }
    }

    @Test @Tag("P1")
    @Disabled("GAP-03 — no certificate entity, so no school-leaving certificate or final transcript "
        + "(Phase 7).")
    void cert_GRAD_02_leavingCertificateAndTranscriptAreGenerated() {
    }

    @Test @Tag("P2")
    @Disabled("Phase 5 gives the report card a content model and a graduate promotion decision, but a "
        + "transcript spanning years is Phase 7's, and the board adapters remain credential-blocked.")
    void cert_GRAD_03_boardResultsAreMergedOntoTheFinalTranscript() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-04 — no alumni identity or scope downgrade after graduation (Phase 7).")
    void cert_GRAD_04_alumniLoginIsDowngradedToDocumentRetrieval() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-04 + GAP-14 — no retention window, no audited alumni document request, and closed "
        + "years are not read-only (Phases 1 and 7).")
    void cert_GRAD_05_alumniDocumentRequestYearsLaterIsServableAndAudited() {
    }

    @Test @Tag("P1")
    void cert_GRAD_06_graduatingStudentsAreExcludedFromNextYearsOperations() {
        var sandbox = rolloverSandbox("grad06");
        String token = sandboxToken(sandbox);
        UUID leaver = sandbox.firstStudent("R3", "A");
        UUID stayer = sandbox.firstStudent("R1", "A");
        try {
            // Both children ride the bus this year.
            for (UUID student : List.of(leaver, stayer)) {
                post("/v1/transport/student-assignments", body(
                    "schoolId", sandbox.schoolId(), "studentId", student, "routeId", sandbox.routeId(),
                    "stopId", sandbox.stopId(), "startsOn", "2026-06-01"), token);
            }

            UUID runId = roll(sandbox, token);
            post("/v1/rollover/runs/" + runId + "/commit", body(), token);
            post("/v1/rollover/runs/" + runId + "/activate",
                body("actingStaffId", sandbox.principalStaffId()), token);

            // Next year's billing run reaches the children who are still here.
            var generated = post("/v1/fees/generate", body(
                "schoolId", sandbox.schoolId(), "academicYearId", sandbox.targetAyId(),
                "cycleLabel", "GRAD06 Term 1", "dueOn", "2026-09-10",
                "runByStaffId", sandbox.principalStaffId()), token);
            assertThat(generated.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(generated.getBody().get("invoicesCreated").asInt()).isEqualTo(20);
            assertThat(count("SELECT count(*) FROM fee_invoice WHERE student_id = ? AND cycle_label = ?",
                leaver, "GRAD06 Term 1")).isZero();
            assertThat(count("SELECT count(*) FROM fee_invoice WHERE student_id = ? AND cycle_label = ?",
                stayer, "GRAD06 Term 1")).isEqualTo(1);

            // The bus seat ends with the year for the leaver and continues for
            // the child who is still coming to school.
            assertThat(count(
                "SELECT count(*) FROM student_transport WHERE student_id = ? AND ends_on IS NULL", leaver))
                .isZero();
            assertThat(count(
                "SELECT count(*) FROM student_transport WHERE student_id = ? AND ends_on IS NULL AND " +
                "  starts_on = '2026-09-01'", stayer)).isEqualTo(1);

            // And communications: announcements are scoped by section, so being
            // on no section of the live year is what takes a graduate off the list.
            assertThat(count(
                "SELECT count(*) FROM enrolment WHERE student_id = ? AND academic_year_id = ? " +
                "  AND status = 'active'", leaver, sandbox.targetAyId())).isZero();
        } finally {
            dropSandbox(sandbox);
        }
    }

    /** Start → clone → allocate against the sandbox's first year. */
    private UUID roll(com.schoolsoft.certification.support.RolloverSandbox.Sandbox sandbox, String token) {
        var run = post("/v1/rollover/runs", body(
            "schoolId", sandbox.schoolId(), "fromAcademicYearId", sandbox.sourceAyId(),
            "toAcademicYearId", sandbox.targetAyId(), "runKey", "cert-" + sandbox.slug(),
            "startedByStaffId", sandbox.principalStaffId()), token);
        UUID runId = UUID.fromString(run.getBody().get("id").asText());
        post("/v1/rollover/runs/" + runId + "/clone-structure", null, token);
        post("/v1/rollover/runs/" + runId + "/allocate", null, token);
        return runId;
    }
}
