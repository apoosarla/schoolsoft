package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-ATT — daily attendance. */
class AttendanceCertTest extends AbstractCertificationTest {

    private static final String MARK_DATE = "2026-08-03";      // a Monday inside the current AY

    @Test @Tag("P1")
    void cert_ATT_01_dayLevelMarkingIsIdempotentOnResubmission() {
        String token = teacherToken(cbse(), 0);
        UUID sectionId = currentFocusSection(cbse());
        List<UUID> students = studentsIn(sectionId);

        var first = post("/v1/attendance/mark/bulk", body(
            "schoolId", cbse().id(), "sectionId", sectionId, "onDate", MARK_DATE,
            "markedByStaffId", cbse().teacherStaffIds().get(0),
            "entries", students.stream().map(s -> Map.of("studentId", s, "status", "present")).toList()), token);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Re-submitting the same day corrects rather than duplicates.
        var second = post("/v1/attendance/mark/bulk", body(
            "schoolId", cbse().id(), "sectionId", sectionId, "onDate", MARK_DATE,
            "markedByStaffId", cbse().teacherStaffIds().get(0),
            "entries", List.of(Map.of("studentId", students.get(0), "status", "absent"))), token);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(count("SELECT count(*) FROM attendance_record WHERE section_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL", sectionId, MARK_DATE)).isEqualTo(students.size());
        assertThat(queryOne("SELECT status FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL", String.class, students.get(0), MARK_DATE)).isEqualTo("absent");
    }

    @Test @Tag("P1")
    void cert_ATT_02_periodAndDayLevelRecordsCoexistForTheSameDate() {
        String token = teacherToken(cbse(), 1);
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = firstStudentIn(sectionId);

        post("/v1/attendance/mark", body("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionId, "onDate", MARK_DATE, "status", "present"), token);
        var periodMark = post("/v1/attendance/mark", body("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionId, "onDate", MARK_DATE, "periodNo", 4, "status", "absent"), token);
        assertThat(periodMark.getStatusCode()).isEqualTo(HttpStatus.OK);

        var forStudent = get("/v1/attendance/students/" + studentId
            + "?from=" + MARK_DATE + "&to=" + MARK_DATE, token).getBody();
        assertThat(forStudent).hasSize(2);
        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no = 4", studentId, MARK_DATE)).isEqualTo(1);
    }

    @Test @Tag("P1")
    @Disabled("No producer raises an absence event: nothing calls DomainEvents or NotificationService from "
        + "the attendance path, so no parent notification is dispatched and the duplicate-suppression rule "
        + "has nothing to suppress. New gap found in Phase 0.")
    void cert_ATT_03_absenceNotifiesTheParentWithoutDuplicating() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-01 — statuses persist correctly, but no monthly attendance percentage is computed "
        + "anywhere (the dashboard reports today's raw present count only), so late/half-day/excused "
        + "weighting has no consumer (Phase 1).")
    void cert_ATT_04_statusesComputeIntoTheMonthlyPercentage() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-08 — approving a leave application updates leave_application only; no attendance "
        + "records are materialised for the covered days (Phase 3).")
    void cert_ATT_05_approvedLeaveMaterialisesAttendance() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-08 + GAP-27 — the upsert overwrites the prior value with no amendment record, no "
        + "approval and no audit row (Phase 3).")
    void cert_ATT_06_correctionAfterLockRequiresAnApprovedAuditedAmendment() {
    }

    @Test @Tag("P1")
    void cert_ATT_07_deviceEventsAreIdempotentPerStudentAndDay() {
        String token = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = studentsIn(sectionId).get(1);
        UUID deviceId = registerDevice("CERT-BIO-" + UUID.randomUUID().toString().substring(0, 6));
        String onDate = "2026-08-04";

        for (int i = 0; i < 3; i++) {
            var ingested = post("/v1/devices/" + deviceId + "/events/student", body(
                "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
                "onDate", onDate, "source", "biometric"), token);
            assertThat(ingested.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL", studentId, onDate)).isEqualTo(1);
        // The device's own date is honoured — the server never re-derives it from a receive time.
        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? "
            + "AND on_date = ?::date + 1", studentId, onDate)).isZero();
    }

    @Test @Tag("P1")
    @Disabled("Device replay lands on the supplied date, but the upsert unconditionally overwrites: a "
        + "manual correction made in the interim is replaced by the replayed 'present'. There is no "
        + "source precedence rule. New gap found in Phase 0.")
    void cert_ATT_08_offlineDeviceBacklogDoesNotOverwriteManualCorrections() {
    }

    @Test @Tag("P2")
    @Disabled("No offline sync protocol: marks carry no client version or timestamp, so a conflicting "
        + "server-side edit is silently overwritten rather than surfaced. New gap found in Phase 0.")
    void cert_ATT_09_offlineTeacherMarkingSurfacesConflicts() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-01 — no working-day denominator and no enrolment-window-aware percentage for a "
        + "mid-year joiner or leaver (Phase 1).")
    void cert_ATT_10_percentageUsesTheEnrolmentWindowAsDenominator() {
    }

    @Test @Tag("P2")
    @Disabled("No chronic-absence report: nothing aggregates attendance over a rolling window per section "
        + "or grade. New gap found in Phase 0.")
    void cert_ATT_11_chronicAbsenceReportMatchesRawRecords() {
    }

    @Test @Tag("P1")
    @Disabled("Attendance accepts a future date and a date outside the student's enrolment window; neither "
        + "is validated in AttendanceRepository.mark. New gap found in Phase 0.")
    void cert_ATT_12_futureOrOutOfWindowDatesAreRefused() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-08 — staff punches are recorded, but an approved staff leave does not surface as "
        + "`leave` in staff_attendance (Phase 3).")
    void cert_ATT_13_staffAttendanceReflectsApprovedLeave() {
    }

    // ---------------------------------------------------------------- helpers

    private UUID registerDevice(String serialNo) {
        var created = post("/v1/devices", body("schoolId", cbse().id(), "kind", "biometric",
            "vendor", "eSSL", "model", "K30", "serialNo", serialNo,
            "location", "Main gate", "apiKey", "cert-device-key"), principalToken(cbse()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }
}
