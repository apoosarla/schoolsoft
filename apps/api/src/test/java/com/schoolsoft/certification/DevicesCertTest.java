package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-DEV — devices & IoT. */
class DevicesCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    @Disabled("GAP-24 — a device is registered to a school but there is no campus to bind it to. The "
        + "unregistered-device half already holds (an unknown device id is rejected); campus scoping "
        + "arrives in Phase 1.")
    void cert_DEV_01_deviceIsRegisteredToSchoolAndCampusAndUnknownDevicesAreRejected() {
    }

    @Test @Tag("P1")
    void cert_DEV_02_eventIngestionIsIdempotentAndOutOfOrderTolerant() {
        String token = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = studentsIn(sectionId).get(3);
        UUID deviceId = registerDevice("CERT-DEV02-" + UUID.randomUUID().toString().substring(0, 6));

        // Newer day first, then an older day replayed out of order.
        ingest(deviceId, studentId, sectionId, "2026-08-07", token);
        ingest(deviceId, studentId, sectionId, "2026-08-06", token);
        ingest(deviceId, studentId, sectionId, "2026-08-07", token);

        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? "
            + "AND on_date IN ('2026-08-06','2026-08-07') AND period_no IS NULL", studentId)).isEqualTo(2);
        assertThat(queryOne("SELECT status FROM attendance_record WHERE student_id = ? "
            + "AND on_date = '2026-08-06' AND period_no IS NULL", String.class, studentId)).isEqualTo("present");

        // An unregistered device id is refused rather than writing attendance.
        var unknown = post("/v1/devices/" + UUID.randomUUID() + "/events/student", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", "2026-08-07", "source", "biometric"), token);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @Tag("P2")
    @Disabled("device.last_seen_at is updated on every event, but nothing surfaces a device that has "
        + "stopped reporting: no heartbeat check and no alert before the day's attendance is affected. "
        + "New gap found in Phase 0.")
    void cert_DEV_03_offlineDeviceIsSurfacedBeforeAttendanceIsAffected() {
    }

    @Test @Tag("P3")
    @Disabled("A device belongs to exactly one school and the ingest trusts the schoolId in the request "
        + "body rather than the device's own registration, so a shared-campus device has no routing rule "
        + "to certify. New gap found in Phase 0.")
    void cert_DEV_04_sharedDeviceRoutesEventsToTheCorrectTenant() {
    }

    // ---------------------------------------------------------------- helpers

    private UUID registerDevice(String serialNo) {
        var created = post("/v1/devices", body("schoolId", cbse().id(), "kind", "rfid_reader",
            "vendor", "ZKTeco", "model", "RF10", "serialNo", serialNo,
            "location", "Gate 2", "apiKey", "cert-device-key"), principalToken(cbse()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }

    private void ingest(UUID deviceId, UUID studentId, UUID sectionId, String onDate, String token) {
        var response = post("/v1/devices/" + deviceId + "/events/student", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", onDate, "source", "rfid"), token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
