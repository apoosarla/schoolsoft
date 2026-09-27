package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import com.schoolsoft.certification.support.CertificationFixture;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * CERT-NFR — non-functional.
 *
 * The load-shaped scenarios need the bulk seed
 * ({@code -Dschoolsoft.cert.bulk-students=2000}) and agreed latency targets;
 * they run in the perf profile rather than the merge gate. What is certifiable
 * without either is here.
 */
class NonFunctionalCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    @Disabled("Perf profile: needs the bulk seed (-Dschoolsoft.cert.bulk-students=2000) and an agreed p95 "
        + "target. Note the correctness half already has a finding — concurrent writes to the same "
        + "attendance day rely on the upsert alone, with no conflict surface (see ATT-09).")
    void cert_NFR_01_morningAttendancePeakHoldsP95WithoutLostWrites() {
    }

    @Test @Tag("P1")
    @Disabled("Perf profile: the report-card content model landed in Phase 5, so this now needs only the "
        + "bulk seed (-Dschoolsoft.cert.bulk-students=2000) and an agreed p95 target.")
    void cert_NFR_02_reportCardPublicationForTwoThousandStudentsStaysInWindow() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-21 — invoice generation is a job as of Phase 4, but notification fan-out is not: "
        + "there is still no queue whose backlog can be measured under load, and the perf fixture needs "
        + "the bulk seed.")
    void cert_NFR_03_feeDueDayGenerationAndFanOutStayWithinSla() {
    }

    @Test @Tag("P2")
    @Disabled("Client-side scenario: low-end Android over 3G and tablet layout are measured against the "
        + "parent app build, not the API suite.")
    void cert_NFR_04_parentAppLoadsOnLowEndAndroidOverThreeG() {
    }

    @Test @Tag("P2")
    @Disabled("Chain-level dashboards over ten schools need the multi-school perf fixture; chain HQ "
        + "analytics is Phase 2 of the design doc and out of scope for this remediation.")
    void cert_NFR_05_chainDashboardsOverTenSchoolsReturnInTarget() {
    }

    @Test @Tag("P1")
    @Disabled("Backup and point-in-time restore are exercised by the ops runbook against a real cluster; "
        + "there is nothing in the application to certify them against.")
    void cert_NFR_06_backupAndPointInTimeRestoreAreVerified() {
    }

    @Test @Tag("P1")
    void cert_NFR_07_chainMigrationOnAPopulatedSchemaIsSafeAndRepeatable() {
        long studentsBefore = count("SELECT count(*) FROM student");
        long marksBefore = count("SELECT count(*) FROM mark");
        long attendanceBefore = count("SELECT count(*) FROM attendance_record");
        Integer versionBefore = platformJdbc.queryForObject(
            "SELECT schema_version FROM platform.chain WHERE slug = ?", Integer.class,
            CertificationFixture.CHAIN_SLUG);

        // Re-running the migration chain against a populated schema — the deploy-time path — is a no-op.
        provisioning.provision(CertificationFixture.CHAIN_SLUG, "Certification Chain", "enterprise");

        assertThat(count("SELECT count(*) FROM student")).isEqualTo(studentsBefore);
        assertThat(count("SELECT count(*) FROM mark")).isEqualTo(marksBefore);
        assertThat(count("SELECT count(*) FROM attendance_record")).isEqualTo(attendanceBefore);
        assertThat(platformJdbc.queryForObject(
            "SELECT schema_version FROM platform.chain WHERE slug = ?", Integer.class,
            CertificationFixture.CHAIN_SLUG)).isEqualTo(versionBefore);
        assertThat(platformJdbc.queryForObject(
            "SELECT last_error FROM platform.chain_schema_version csv "
            + "JOIN platform.chain c ON c.id = csv.chain_id WHERE c.slug = ?",
            String.class, CertificationFixture.CHAIN_SLUG)).isNull();
    }

    /**
     * The clock is pinned to instants whose UTC date and IST date differ, on a
     * day other than the real one — so neither the JVM's zone nor the wall
     * clock can pass for the school's.
     */
    @Test @Tag("P1")
    void cert_NFR_08_timezoneCorrectnessKeepsDateOnlyFieldsUnshifted() {
        String token = principalToken(cie());
        UUID sectionId = currentFocusSection(cie());
        UUID studentId = studentsIn(sectionId).get(2);
        UUID staffId = queryOne("SELECT subject_id FROM user_account WHERE id = ?", UUID.class,
            cie().principalUserId());
        var device = post("/v1/devices", body("schoolId", cie().id(), "kind", "biometric",
            "vendor", "eSSL", "model", "K30", "serialNo", "CERT-NFR08-" + UUID.randomUUID().toString().substring(0, 6),
            "location", "Main gate", "apiKey", "cert-device-key"), token);
        assertThat(device.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID deviceId = UUID.fromString(device.getBody().get("id").asText());

        try {
            // 23:55 IST on 25 August: 18:25 UTC, the same date either way.
            clock.pin(Instant.parse("2026-08-25T18:25:00Z"));
            assertThat(post("/v1/devices/" + deviceId + "/events/student", body(
                "schoolId", cie().id(), "studentId", studentId, "sectionId", sectionId, "source", "biometric"),
                token).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? "
                + "AND on_date = '2026-08-25' AND period_no IS NULL AND source = 'biometric'", studentId)).isEqualTo(1);

            // 00:30 IST on 26 August is still the 25th in UTC. The school's day has turned.
            clock.pin(Instant.parse("2026-08-25T19:00:00Z"));
            assertThat(post("/v1/devices/" + deviceId + "/events/student", body(
                "schoolId", cie().id(), "studentId", studentId, "sectionId", sectionId, "source", "biometric"),
                token).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? "
                + "AND on_date = '2026-08-26' AND period_no IS NULL AND source = 'biometric'", studentId)).isEqualTo(1);

            assertThat(post("/v1/devices/" + deviceId + "/events/staff", body(
                "schoolId", cie().id(), "staffId", staffId, "checkIn", true), token)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(count("SELECT count(*) FROM staff_attendance WHERE staff_id = ? AND on_date = '2026-08-26'",
                staffId)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM staff_attendance WHERE staff_id = ? AND on_date = '2026-08-25'",
                staffId)).isZero();
        } finally {
            clock.release();
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM attendance_record WHERE student_id = ? AND source = 'biometric' "
                    + "AND on_date IN ('2026-08-25', '2026-08-26') AND period_no IS NULL", studentId);
                jdbc.update("DELETE FROM staff_attendance WHERE staff_id = ? AND on_date IN ('2026-08-25', '2026-08-26') "
                    + "AND source = 'biometric'", staffId);
                jdbc.update("DELETE FROM device WHERE id = ?", deviceId);
            });
        }

        // A date-only field reads back as the day that was written, whatever
        // the server's zone: a date of birth is never an instant.
        String dob = queryOne("SELECT dob::text FROM student WHERE id = ?", String.class, studentId);
        var student = get("/v1/people/students/" + studentId, token);
        assertThat(student.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(student.getBody().get("dob").asText()).isEqualTo(dob);
    }

    @Test @Tag("P2")
    @Disabled("No localisation layer: currency and date formatting live in each frontend, and there are no "
        + "generated documents yet to check (report card rendering is Phase 5).")
    void cert_NFR_09_localisationOfNamesCurrencyAndDatesIsConsistent() {
    }

    @Test @Tag("P2")
    @Disabled("No alerting path: failures are logged, but nothing raises an actionable alert carrying "
        + "tenant context. New gap found in Phase 0.")
    void cert_NFR_10_failuresProduceActionableAlertsWithTenantContext() {
    }
}
