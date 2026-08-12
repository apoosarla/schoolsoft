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
    void cert_ATT_04_statusesComputeIntoTheMonthlyPercentage() {
        String token = teacherToken(cbse(), 0);
        UUID sectionId = currentFocusSection(cbse());
        // A student no other scenario marks, so the week is this test's alone.
        UUID studentId = studentsIn(sectionId).get(5);

        Map<String, String> week = Map.of(
            "2026-08-03", "present",
            "2026-08-04", "late",
            "2026-08-05", "half_day",
            "2026-08-06", "excused",
            "2026-08-07", "absent");
        week.forEach((date, status) -> {
            var marked = post("/v1/attendance/mark", body(
                "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
                "onDate", date, "status", status, "markedByStaffId", cbse().teacherStaffIds().get(0)), token);
            assertThat(marked.getStatusCode()).isEqualTo(HttpStatus.OK);
        });

        var summary = get("/v1/attendance/students/" + studentId
            + "/summary?from=2026-08-03&to=2026-08-07", token);
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = summary.getBody();

        assertThat(body.get("workingDays").asInt()).isEqualTo(5);
        // Excused leaves the denominator rather than counting as an absence.
        assertThat(body.get("consideredDays").asInt()).isEqualTo(4);
        assertThat(body.get("present").asInt()).isEqualTo(1);
        assertThat(body.get("late").asInt()).isEqualTo(1);
        assertThat(body.get("halfDay").asInt()).isEqualTo(1);
        assertThat(body.get("excused").asInt()).isEqualTo(1);
        assertThat(body.get("absent").asInt()).isEqualTo(1);
        // Present + late (present, just not on time) + half a day, over four days.
        assertThat(body.get("percentage").asDouble()).isEqualTo(62.5);
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
    void cert_ATT_10_percentageUsesTheEnrolmentWindowAsDenominator() {
        String token = principalToken(cbse());
        // Section B, not the focus section: these two enrolments would otherwise
        // join the roster every other attendance scenario marks.
        UUID sectionId = sectionOf(cbse(), cbse().currentAy().code(), cbse().focusGradeCode(), "B");

        // Joined on 15 July: the 13 working days from then to month end are the
        // whole of their denominator, not July's 23.
        UUID joiner = createStudent("ATT10-JOIN-" + UUID.randomUUID().toString().substring(0, 6));
        enrol(joiner, sectionId, "2026-07-15", null, token);

        var joinerSummary = get("/v1/attendance/students/" + joiner
            + "/summary?from=2026-07-01&to=2026-07-31", token);
        assertThat(joinerSummary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(joinerSummary.getBody().get("workingDays").asInt()).isEqualTo(13);
        assertThat(joinerSummary.getBody().get("enrolledFrom").asText()).isEqualTo("2026-07-15");

        // Left on 15 July: the 11 working days up to then.
        UUID leaver = createStudent("ATT10-LEFT-" + UUID.randomUUID().toString().substring(0, 6));
        enrol(leaver, sectionId, "2026-07-01", "2026-07-15", token);

        var leaverSummary = get("/v1/attendance/students/" + leaver
            + "/summary?from=2026-07-01&to=2026-07-31", token);
        assertThat(leaverSummary.getBody().get("workingDays").asInt()).isEqualTo(11);
        assertThat(leaverSummary.getBody().get("enrolledTo").asText()).isEqualTo("2026-07-15");

        // A full-year student over the same month is measured against all 23.
        var fullYear = get("/v1/attendance/students/" + firstStudentIn(currentFocusSection(cbse()))
            + "/summary?from=2026-07-01&to=2026-07-31", token);
        assertThat(fullYear.getBody().get("workingDays").asInt()).isEqualTo(23);

        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM enrolment WHERE student_id IN (?, ?)", joiner, leaver);
            jdbc.update("DELETE FROM student WHERE id IN (?, ?)", joiner, leaver);
        });
    }

    @Test @Tag("P2")
    @Disabled("No chronic-absence report: nothing aggregates attendance over a rolling window per section "
        + "or grade. New gap found in Phase 0.")
    void cert_ATT_11_chronicAbsenceReportMatchesRawRecords() {
    }

    @Test @Tag("P1")
    void cert_ATT_12_futureOrOutOfWindowDatesAreRefused() {
        String token = teacherToken(cbse(), 0);
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = studentsIn(sectionId).get(7);
        String futureDate = java.time.LocalDate.now().plusDays(7).toString();

        var future = post("/v1/attendance/mark", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", futureDate, "status", "present"), token);
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(future.getBody().get("message").asText()).contains("future date");

        // The student's enrolment in this section starts with the current AY, so
        // a date from the previous year is outside their window here.
        var beforeEnrolment = post("/v1/attendance/mark", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", "2026-03-03", "status", "present"), token);
        assertThat(beforeEnrolment.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(beforeEnrolment.getBody().get("message").asText()).contains("not enrolled");

        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? "
            + "AND on_date IN (?::date, '2026-03-03')", studentId, futureDate)).isZero();
    }

    @Test @Tag("P2")
    @Disabled("GAP-08 — staff punches are recorded, but an approved staff leave does not surface as "
        + "`leave` in staff_attendance (Phase 3).")
    void cert_ATT_13_staffAttendanceReflectsApprovedLeave() {
    }

    // ---------------------------------------------------------------- helpers

    private UUID createStudent(String admissionNo) {
        var created = post("/v1/people/students", Map.of(
            "schoolId", cbse().id(), "admissionNo", admissionNo,
            "firstName", "Certification", "lastName", "Candidate",
            "dob", "2015-05-05", "gender", "male"), principalToken(cbse()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }

    /** Enrols, then closes the window when {@code endsOn} is given. */
    private void enrol(UUID studentId, UUID sectionId, String startsOn, String endsOn, String token) {
        var enrolled = post("/v1/enrolment", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "academicYearId", cbse().currentAy().id(), "startsOn", startsOn), token);
        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.OK);
        if (endsOn != null) {
            UUID enrolmentId = UUID.fromString(enrolled.getBody().get("id").asText());
            var closed = post("/v1/enrolment/" + enrolmentId + "/status",
                body("status", "withdrawn", "endsOn", endsOn), token);
            assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    private UUID registerDevice(String serialNo) {
        var created = post("/v1/devices", body("schoolId", cbse().id(), "kind", "biometric",
            "vendor", "eSSL", "model", "K30", "serialNo", serialNo,
            "location", "Main gate", "apiKey", "cert-device-key"), principalToken(cbse()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }
}
