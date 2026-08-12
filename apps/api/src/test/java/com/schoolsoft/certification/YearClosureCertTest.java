package com.schoolsoft.certification;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CERT-YEC — academic year closure & rollover.
 *
 * Entirely blocked on GAP-02 (no rollover) and GAP-14 (no closed-year lock).
 * Phase 6 of the remediation plan builds this on top of the calendar (Phase 1),
 * capacity (Phase 2), the arrears balance (Phase 4) and the promotion decision
 * (Phase 5) — which is why it cannot be brought forward.
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
    @Disabled("GAP-02 + GAP-10 — no reshuffle rules, and capacity is not enforced anywhere (Phases 2 "
        + "and 6).")
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
    @Disabled("GAP-14 — academic_year has only is_current: a closed year stays mutable and there is no "
        + "authorised reopen path (Phase 1).")
    void cert_YEC_08_closedYearIsReadOnlyWithoutAnAuthorisedReopen() {
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
