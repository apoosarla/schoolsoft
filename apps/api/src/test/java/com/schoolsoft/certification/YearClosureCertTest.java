package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * CERT-YEC — academic year closure & rollover.
 *
 * The closed-year lock (GAP-14) landed in Phase 1, so YEC-08 runs. Everything
 * else here is blocked on GAP-02 (no rollover): Phase 6 builds it on top of the
 * calendar (Phase 1), capacity (Phase 2), the arrears balance (Phase 4) and the
 * promotion decision (Phase 5) — which is why it cannot be brought forward.
 */
class YearClosureCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    @Disabled("GAP-02 — no readiness check: nothing lists unpublished assessments, unlocked report cards, "
        + "unmarked days or outstanding dues before closure (Phase 6).")
    void cert_YEC_01_readinessCheckListsEverythingBlockingClosure() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-02 — no structure clone into the next AY (Phase 6).")
    void cert_YEC_02_nextYearStructureIsClonedAndEditableBeforeActivation() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-02 — enrolment.status allows 'promoted' but nothing sets it; there is no bulk "
        + "promotion (Phase 6).")
    void cert_YEC_03_bulkPromotionMovesTheCohortAndClosesOldEnrolments() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-02 — no detain path, because there is no promotion decision to act on (Phases 5 and 6).")
    void cert_YEC_04_detainedStudentStaysInGradeWithPreservedHistory() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-02 — capacity is enforced as of Phase 2, but there are no reshuffle rules to apply it "
        + "to: allocation across sections arrives with rollover (Phase 6).")
    void cert_YEC_05_sectionReshuffleRespectsCapacityAndSiblingPolicy() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-02 — nothing carries fee arrears, library dues, transport assignment, guardian links or "
        + "medical info into the next year (Phase 6).")
    void cert_YEC_06_rolloverCarriesForwardDuesAssignmentsAndLinks() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-02 — no rollover_run, so idempotency and roll-back before activation have nothing to "
        + "key on (Phase 6).")
    void cert_YEC_07_rolloverIsIdempotentAndReversible() {
    }

    @Test @Tag("P1")
    void cert_YEC_08_closedYearIsReadOnlyWithoutAnAuthorisedReopen() {
        String token = principalToken(cbse());
        UUID priorAy = cbse().priorAy().id();
        UUID priorSection = priorFocusSection(cbse());
        UUID studentId = firstStudentIn(priorSection);
        String priorDate = "2025-07-07";                        // a Monday in the prior year's history
        String statusPath = "/v1/tenancy/academic-years/" + priorAy + "/status";

        UUID priorComponent = queryOne(
            "SELECT ac.id FROM assessment_component ac JOIN assessment a ON a.id = ac.assessment_id " +
            "WHERE a.section_id = ? LIMIT 1", UUID.class, priorSection);
        UUID priorInvoice = queryOne(
            "SELECT fi.id FROM fee_invoice fi JOIN academic_year ay ON ay.school_id = fi.school_id " +
            "WHERE ay.id = ? AND fi.issued_on BETWEEN ay.starts_on AND ay.ends_on " +
            "  AND fi.student_id = ? LIMIT 1", UUID.class, priorAy, studentId);

        var closed = post(statusPath, body(
            "status", "closed", "actingStaffId", cbse().principalStaffId(),
            "reason", "Year-end closure"), token);
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody().get("status").asText()).isEqualTo("closed");

        try {
            // Attendance, marks and fees for the closed year all refuse the write.
            var attendance = post("/v1/attendance/mark", body(
                "schoolId", cbse().id(), "studentId", studentId, "sectionId", priorSection,
                "onDate", priorDate, "status", "absent"), token);
            assertThat(attendance.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            var mark = post("/v1/assessment/components/" + priorComponent + "/marks", body(
                "schoolId", cbse().id(), "studentId", studentId, "rawMarks", 91.0,
                "enteredByStaffId", cbse().teacherStaffIds().get(0)), token);
            assertThat(mark.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            var payment = post("/v1/fees/payments", body(
                "schoolId", cbse().id(), "feeInvoiceId", priorInvoice, "amount", 100.0,
                "gateway", "manual", "method", "cash",
                "idempotencyKey", "yec08-" + UUID.randomUUID()), accountantToken(cbse()));
            assertThat(payment.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // Reading it is still fine — closure is read-only, not invisible.
            assertThat(get("/v1/attendance/students/" + studentId
                + "?from=2025-07-01&to=2025-07-31", token).getStatusCode()).isEqualTo(HttpStatus.OK);

            // Reopening demands a reason, and records who did it.
            var unreasoned = post(statusPath, body(
                "status", "active", "actingStaffId", cbse().principalStaffId()), token);
            assertThat(unreasoned.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            var reopened = post(statusPath, body(
                "status", "active", "actingStaffId", cbse().principalStaffId(),
                "reason", "Board asked for a corrected mark"), token);
            assertThat(reopened.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(reopened.getBody().get("status").asText()).isEqualTo("active");
            assertThat(reopened.getBody().get("reopenReason").asText())
                .isEqualTo("Board asked for a corrected mark");
            assertThat(queryOne("SELECT reopened_by_staff_id FROM academic_year WHERE id = ?",
                UUID.class, priorAy)).isEqualTo(cbse().principalStaffId());

            var afterReopen = post("/v1/attendance/mark", body(
                "schoolId", cbse().id(), "studentId", studentId, "sectionId", priorSection,
                "onDate", priorDate, "status", "absent"), token);
            assertThat(afterReopen.getStatusCode()).isEqualTo(HttpStatus.OK);

            assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'academic_year.status_changed' "
                + "AND target_id = ?", priorAy)).isGreaterThanOrEqualTo(2);
        } finally {
            inChainDo(jdbc -> jdbc.update(
                "UPDATE academic_year SET status = 'active', closed_at = NULL, closed_by_staff_id = NULL, " +
                "  reopened_at = NULL, reopened_by_staff_id = NULL, reopen_reason = NULL WHERE id = ?", priorAy));
        }
    }

    @Test @Tag("P1")
    @Disabled("GAP-02 — historical reporting across two rollovers cannot be exercised until a rollover "
        + "exists (Phase 6). The fixture already carries one prior year of history for this scenario to "
        + "assert against once it does.")
    void cert_YEC_09_historicalReportingSurvivesTwoRollovers() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-02 — teacher assignments cannot fail to carry forward until something carries anything "
        + "forward (Phase 6).")
    void cert_YEC_10_teacherAssignmentsDoNotSilentlyCarryForward() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-02 — no rollover to time or restart (Phase 6). The bulk seed for this scenario is "
        + "available via -Dschoolsoft.cert.bulk-students.")
    void cert_YEC_11_twoThousandStudentRolloverCompletesAndIsRestartable() {
    }
}
