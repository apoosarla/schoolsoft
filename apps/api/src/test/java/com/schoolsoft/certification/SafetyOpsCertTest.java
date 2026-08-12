package com.schoolsoft.certification;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CERT-OPS — safety & day-to-day administration. */
class SafetyOpsCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    @Disabled("GAP-18 — no gate pass or early-dismissal approval chain, and no half-day write-back "
        + "(Phase 8, on top of Phase 3's amendment path).")
    void cert_OPS_01_gatePassApprovalRecordsGateOutAndAHalfDay() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-18 — no authorised-pickup list to enforce at dismissal and nowhere to log a refusal "
        + "(Phase 8).")
    void cert_OPS_02_authorisedPickupListIsEnforcedAtDismissal() {
    }

    @Test @Tag("P3")
    @Disabled("GAP-18 — no visitor log (Phase 8).")
    void cert_OPS_03_visitorLogRecordsCheckInAndHost() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-19 — no discipline incident record, which also leaves the TC conduct line unbacked "
        + "(Phase 8).")
    void cert_OPS_04_disciplineIncidentIsRecordedAndFeedsTheConductLine() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-17 — no infirmary visit log and no medical history to restrict access to (Phase 8).")
    void cert_OPS_05_infirmaryVisitIsLoggedAndTheGuardianNotified() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-19 — no counselling notes and no restricted access class to hold them under "
        + "(Phase 8).")
    void cert_OPS_06_counsellingNotesAreRestrictedFromGeneralStaff() {
    }

    @Test @Tag("P3")
    @Disabled("GAP-18 — no evacuation roster built from live attendance (Phase 8).")
    void cert_OPS_07_evacuationRosterIsProducedFromLiveAttendance() {
    }
}
