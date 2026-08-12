package com.schoolsoft.certification;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CERT-INT — board & external integration. */
class IntegrationCertTest extends AbstractCertificationTest {

    @Test @Tag("P2")
    @Disabled("A job can be enqueued and processed to 'completed' against the stub adapter, but a failed "
        + "job is terminal: process() accepts only status 'queued' and nothing re-queues, so the "
        + "retry-without-duplication half cannot pass. New gap found in Phase 0.")
    void cert_INT_01_exportJobIsProcessedAndRetryableWithoutDuplication() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-05 — an export payload cannot represent students whose subject set differs from their "
        + "section's, because student-level election does not exist (Phase 2).")
    void cert_INT_02_exportPayloadValidatesForACohortIncludingElectives() {
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
