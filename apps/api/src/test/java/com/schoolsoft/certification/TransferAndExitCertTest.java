package com.schoolsoft.certification;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CERT-XFER — transfer out, withdrawal & TC. */
class TransferAndExitCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    @Disabled("GAP-03 — enrolment.status can be set to 'withdrawn', but there is no withdrawal entity, no "
        + "reason, no last-working-date and no clearance checklist (Phase 7).")
    void cert_XFER_01_withdrawalRunsAClearanceChecklist() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-03 — no certificate entity and no TC generation with statutory fields or a serial "
        + "number (Phase 7, using the number series from Phase 2).")
    void cert_XFER_02_transferCertificateCarriesStatutoryFieldsAndIsNumbered() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-03 — there is no single enrolment-active-on-date predicate, so setting a status to "
        + "'withdrawn' does not remove the student from rosters, timetable, transport or communications "
        + "(Phase 7).")
    void cert_XFER_03_withdrawalDeListsTheStudentEverywhereButKeepsHistory() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-03 + GAP-04 — no post-withdrawal access scope and no revocation window (Phase 7).")
    void cert_XFER_04_postWithdrawalParentAccessIsReadOnlyThenRevoked() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-15 — /enrolments/{id}/transfer moves a section only; there is no intra-chain school "
        + "transfer that preserves history and settles the source ledger (Phase 7).")
    void cert_XFER_05_intraChainSchoolTransferPreservesHistoryAndSettlesTheLedger() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-16 — no document store for prior-school records, so external marks cannot be held as "
        + "historical context (Phase 8).")
    void cert_XFER_06_externalTransferInCapturesPriorSchoolMarksAsContext() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-03 — with no certificate entity there is no duplicate-issue prevention (Phase 7).")
    void cert_XFER_07_duplicateOrInvalidTcRequestIsPrevented() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-03 — no clearance probe against fees, so withdrawal with dues is neither blocked nor "
        + "overridable with a reason (Phase 7).")
    void cert_XFER_08_withdrawalWithDuesIsBlockedOrOverriddenWithReason() {
    }
}
