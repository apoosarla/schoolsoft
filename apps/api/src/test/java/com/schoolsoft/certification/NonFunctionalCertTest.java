package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import com.schoolsoft.certification.support.CertificationFixture;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
    @Disabled("Perf profile: report-card publication for 2,000 students needs the bulk seed and the "
        + "report-card content model of GAP-13 (Phase 5).")
    void cert_NFR_02_reportCardPublicationForTwoThousandStudentsStaysInWindow() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-09 + GAP-21 — invoice generation and notification fan-out do not exist as jobs, so "
        + "there is no queue to measure backlog on (Phases 4 and 8).")
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

    @Test @Tag("P1")
    @Disabled("Date derivation uses the JVM default zone, not the school's: DeviceController falls back to "
        + "LocalDate.now() and EnrolmentRepository.transfer uses LocalDate.now(), while school.timezone is "
        + "stored and never read. On a UTC server a 23:55 IST event lands on the next day. New gap found "
        + "in Phase 0.")
    void cert_NFR_08_timezoneCorrectnessKeepsDateOnlyFieldsUnshifted() {
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
