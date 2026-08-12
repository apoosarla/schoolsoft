package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-ENR — enrolment & student records. */
class EnrolmentCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_ENR_01_studentHasExactlyOneActiveEnrolment() {
        String token = principalToken(cbse());
        UUID studentId = createStudent("ENR01-" + UUID.randomUUID().toString().substring(0, 8));
        UUID sectionId = currentFocusSection(cbse());

        var enrolled = post("/v1/enrolment", Map.of("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionId, "academicYearId", cbse().currentAy().id(),
            "startsOn", "2026-08-01", "rollNo", "99"), token);
        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enrolled.getBody().get("status").asText()).isEqualTo("active");

        // A second active enrolment is refused rather than silently created.
        var second = post("/v1/enrolment", Map.of("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionOf(cbse(), cbse().currentAy().code(), "5", "B"),
            "academicYearId", cbse().currentAy().id(), "startsOn", "2026-08-01", "rollNo", "98"), token);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(count("SELECT count(*) FROM enrolment WHERE student_id = ? AND status = 'active'", studentId))
            .isEqualTo(1);
    }

    @Test @Tag("P1")
    @Disabled("GAP-26 — roll_no is free text with no uniqueness constraint and no generator, and admission "
        + "numbers have no scheme; nothing renumbers a section after a transfer (Phase 2).")
    void cert_ENR_02_admissionAndRollNumbersFollowSchoolPolicy() {
    }

    @Test @Tag("P1")
    void cert_ENR_03_midYearSectionChangeKeepsHistoryAttributedPerDate() {
        String token = principalToken(cbse());
        UUID studentId = createStudent("ENR03-" + UUID.randomUUID().toString().substring(0, 8));
        UUID sectionA = currentFocusSection(cbse());
        UUID sectionB = sectionOf(cbse(), cbse().currentAy().code(), cbse().focusGradeCode(), "B");

        var enrolled = post("/v1/enrolment", Map.of("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionA, "academicYearId", cbse().currentAy().id(),
            "startsOn", "2026-04-01", "rollNo", "97"), token);
        UUID enrolmentId = UUID.fromString(enrolled.getBody().get("id").asText());

        // Attendance marked while in section A.
        post("/v1/attendance/mark", Map.of("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionA, "onDate", "2026-07-06", "status", "present"), token);

        var transferred = post("/v1/enrolment/" + enrolmentId + "/transfer",
            Map.of("newSectionId", sectionB, "rollNo", "12"), token);
        assertThat(transferred.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transferred.getBody().get("sectionId").asText()).isEqualTo(sectionB.toString());

        var history = get("/v1/enrolment/students/" + studentId, token).getBody();
        assertThat(history).hasSize(2);
        assertThat(queryOne("SELECT status FROM enrolment WHERE id = ?", String.class, enrolmentId))
            .isEqualTo("transferred");
        assertThat(queryOne("SELECT ends_on IS NOT NULL FROM enrolment WHERE id = ?", Boolean.class, enrolmentId))
            .isTrue();

        // The old mark stays attached to the student and to the section it was taken in.
        var marks = get("/v1/attendance/students/" + studentId + "?from=2026-07-01&to=2026-07-31", token).getBody();
        assertThat(marks).hasSize(1);
        assertThat(marks.get(0).get("sectionId").asText()).isEqualTo(sectionA.toString());
    }

    @Test @Tag("P1")
    @Disabled("GAP-18 — guardian_student distinguishes primary/secondary and custody, but a non-guardian "
        + "authorised pickup contact cannot be modelled at all (Phase 8).")
    void cert_ENR_04_guardianAndPickupContactRolesAreDistinguishable() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-18 — custody restrictions on pickup have nowhere to live (Phase 8).")
    void cert_ENR_05_separatedParentsBothReceiveCommsWithCustodyRulesEnforced() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-17 — no health/emergency model: conditions, allergies and prioritised emergency contacts "
        + "are not stored, so they cannot reach the class teacher or the driver view (Phase 8).")
    void cert_ENR_06_healthAndEmergencyDataReachesTeacherAndDriver() {
    }

    @Test @Tag("P3")
    @Disabled("GAP-16 — no student document/photo store, so no ID-card generation for a section (Phase 8).")
    void cert_ENR_07_studentPhotoAndIdCardGenerationForASection() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-02 — re-admission after withdrawal depends on rollover-aware enrolment history "
        + "(Phase 6).")
    void cert_ENR_08_withdrawnStudentIsReadmittedWithoutDuplicating() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-23 — no CSV bulk import for students/staff/marks and no per-row validation (Phase 8).")
    void cert_ENR_09_bulkImportOf500StudentsValidatesPerRow() {
    }

    @Test @Tag("P2")
    void cert_ENR_10_studentSearchIsScopedToTheCallersSchool() {
        String cbseToken = principalToken(cbse());
        var hits = get("/v1/people/students?schoolId=" + cbse().id() + "&q=Student1", cbseToken).getBody();
        assertThat(hits).isNotEmpty();

        // Same query, but pointed at the other school in the chain: row-level security answers with nothing.
        var crossSchool = get("/v1/people/students?schoolId=" + cie().id() + "&q=Student1", cbseToken).getBody();
        assertThat(crossSchool).isEmpty();
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
}
