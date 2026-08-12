package com.schoolsoft.certification;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CERT-GRAD — graduation & alumni. */
class GraduationCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    @Disabled("GAP-02 — graduation is an outcome of rollover, which does not exist: nothing closes a "
        + "terminal-grade cohort as 'graduated' (Phase 6).")
    void cert_GRAD_01_terminalGradeCohortGraduatesAtYearEnd() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-03 — no certificate entity, so no school-leaving certificate or final transcript "
        + "(Phase 7).")
    void cert_GRAD_02_leavingCertificateAndTranscriptAreGenerated() {
    }

    @Test @Tag("P2")
    @Disabled("Board integration is a stub adapter and there is no transcript to merge results onto "
        + "(GAP-13, Phase 5; adapters remain credential-blocked).")
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
    @Disabled("GAP-02 — with no rollover, graduating students cannot be excluded from next year's fee "
        + "generation, transport roster or communications (Phase 6).")
    void cert_GRAD_06_graduatingStudentsAreExcludedFromNextYearsOperations() {
    }
}
