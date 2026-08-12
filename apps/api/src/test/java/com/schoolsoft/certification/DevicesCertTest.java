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
    void cert_DEV_01_deviceIsRegisteredToSchoolAndCampusAndUnknownDevicesAreRejected() {
        String token = principalToken(cbse());
        String serial = "CERT-DEV01-" + UUID.randomUUID().toString().substring(0, 6);

        var registered = post("/v1/devices", body("schoolId", cbse().id(), "campusId", cbse().annexCampusId(),
            "kind", "biometric", "vendor", "eSSL", "model", "K30", "serialNo", serial,
            "location", "Annex gate", "apiKey", "cert-device-key"), token);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID deviceId = UUID.fromString(registered.getBody().get("id").asText());
        assertThat(UUID.fromString(registered.getBody().get("campusId").asText()))
            .isEqualTo(cbse().annexCampusId());

        try {
            // The registry can be read per campus — which gate a reader hangs on
            // is the question an operator actually asks.
            var onAnnex = get("/v1/devices?schoolId=" + cbse().id() + "&campusId=" + cbse().annexCampusId(), token);
            assertThat(onAnnex.getBody()).hasSize(1);
            var onMain = get("/v1/devices?schoolId=" + cbse().id() + "&campusId=" + cbse().mainCampusId(), token);
            assertThat(onMain.getBody().findValuesAsText("id")).doesNotContain(deviceId.toString());

            // A campus from another school is refused rather than stored.
            var wrongCampus = post("/v1/devices", body("schoolId", cbse().id(),
                "campusId", cie().mainCampusId(), "kind", "rfid_reader", "serialNo", serial + "-X",
                "location", "Nowhere", "apiKey", "cert-device-key"), token);
            assertThat(wrongCampus.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            // An unregistered device writes nothing.
            UUID unknownDevice = UUID.randomUUID();
            UUID studentId = firstStudentIn(currentFocusSection(cbse()));
            var rejected = post("/v1/devices/" + unknownDevice + "/events/student", body(
                "schoolId", cbse().id(), "studentId", studentId, "sectionId", currentFocusSection(cbse()),
                "onDate", "2026-08-05", "source", "biometric"), token);
            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? "
                + "AND on_date = '2026-08-05' AND source = 'biometric'", studentId)).isZero();
        } finally {
            inChainDo(jdbc -> jdbc.update("DELETE FROM device WHERE id = ?", deviceId));
        }
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
