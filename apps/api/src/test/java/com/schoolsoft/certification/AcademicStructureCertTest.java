package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/** CERT-ACAD — academic year & structure setup. */
class AcademicStructureCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_ACAD_01_newCurrentAcademicYearDemotesThePreviousOne() {
        String token = principalToken(cie());
        var created = post("/v1/tenancy/schools/" + cie().id() + "/academic-years",
            Map.of("code", "2027-28", "startsOn", "2027-04-01", "endsOn", "2028-03-31", "isCurrent", true),
            token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID newAyId = UUID.fromString(created.getBody().get("id").asText());

        try {
            assertThat(count("SELECT count(*) FROM academic_year WHERE school_id = ? AND is_current", cie().id()))
                .isEqualTo(1);
            assertThat(queryOne("SELECT is_current FROM academic_year WHERE id = ?", Boolean.class,
                cie().currentAy().id())).isFalse();
            assertThat(queryOne("SELECT is_current FROM academic_year WHERE id = ?", Boolean.class, newAyId)).isTrue();
        } finally {
            // Restore the fixture's notion of "current" for the scenarios that follow.
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM academic_year WHERE id = ?", newAyId);
                jdbc.update("UPDATE academic_year SET is_current = TRUE WHERE id = ?", cie().currentAy().id());
            });
        }
    }

    @Test @Tag("P1")
    void cert_ACAD_02_termOutsideTheYearOrOverlappingTermsAreRejected() {
        String token = principalToken(cbse());
        UUID ayId = cbse().currentAy().id();
        String termsPath = "/v1/tenancy/academic-years/" + ayId + "/terms";

        // The AY runs 2026-04-01 .. 2027-03-31; this term starts before it.
        var outside = post(termsPath, Map.of(
            "code", "T0", "name", "Pre-year term", "startsOn", "2026-03-01", "endsOn", "2026-05-31"), token);
        assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(outside.getBody().get("message").asText()).contains("outside academic year");

        // T1 and T2 already exist in the fixture; anything straddling them collides.
        var overlapping = post(termsPath, Map.of(
            "code", "T1B", "name", "Overlapping term", "startsOn", "2026-06-01", "endsOn", "2026-11-30"), token);
        assertThat(overlapping.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(overlapping.getBody().get("message").asText()).contains("overlaps");

        assertThat(count("SELECT count(*) FROM term WHERE academic_year_id = ?", ayId)).isEqualTo(2);

        // The database enforces it too, so a racing writer cannot slip past the
        // pre-check the API does.
        assertThatThrownBy(() -> inChainDo(jdbc -> jdbc.update(
            "INSERT INTO term (id, academic_year_id, code, name, starts_on, ends_on) " +
            "VALUES (gen_random_uuid(), ?, 'T9', 'Direct write', '2026-03-01', '2026-05-31')", ayId)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Tag("P2")
    void cert_ACAD_03_overlappingAcademicYearsAreRejected() {
        String token = principalToken(cbse());

        var overlapping = post("/v1/tenancy/schools/" + cbse().id() + "/academic-years",
            Map.of("code", "2026-27-DUP", "startsOn", "2026-10-01", "endsOn", "2027-09-30", "isCurrent", false),
            token);
        assertThat(overlapping.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(overlapping.getBody().get("message").asText()).contains("overlaps");
        assertThat(count("SELECT count(*) FROM academic_year WHERE school_id = ?", cbse().id())).isEqualTo(2);

        // A year that abuts without overlapping is fine, and is cleaned up after.
        var abutting = post("/v1/tenancy/schools/" + cbse().id() + "/academic-years",
            Map.of("code", "2027-28", "startsOn", "2027-04-01", "endsOn", "2028-03-31", "isCurrent", false),
            token);
        assertThat(abutting.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID abuttingId = UUID.fromString(abutting.getBody().get("id").asText());
        inChainDo(jdbc -> jdbc.update("DELETE FROM academic_year WHERE id = ?", abuttingId));

        // And the exclusion constraint holds against a direct write.
        assertThatThrownBy(() -> inChainDo(jdbc -> jdbc.update(
            "INSERT INTO academic_year (id, school_id, code, starts_on, ends_on, is_current) " +
            "VALUES (gen_random_uuid(), ?, '2026-27-RAW', '2026-10-01', '2027-09-30', FALSE)", cbse().id())))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test @Tag("P1")
    void cert_ACAD_04_gradesAndSectionsAreCreatedWithOrderAndCurriculumBinding() {
        String token = principalToken(cbse());

        var grades = get("/v1/tenancy/schools/" + cbse().id() + "/grades", token).getBody();
        assertThat(grades).hasSize(cbse().gradeCodes().size());
        assertThat(grades.get(0).get("sortOrder").asInt()).isLessThan(grades.get(1).get("sortOrder").asInt());

        var created = post("/v1/tenancy/schools/" + cbse().id() + "/sections",
            Map.of("gradeId", gradeOf(cbse(), "5"), "academicYearId", cbse().currentAy().id(),
                "code", "C", "name", "Grade 5-C", "strategyCode", cbse().strategyCode(), "capacity", 30),
            token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID sectionId = UUID.fromString(created.getBody().get("id").asText());

        var bound = put("/v1/tenancy/sections/" + sectionId + "/curriculum",
            Map.of("curriculumId", cbse().curriculumId(), "strategyCode", cbse().strategyCode()), token);
        assertThat(bound.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(queryOne("SELECT curriculum_id FROM section WHERE id = ?", UUID.class, sectionId))
            .isEqualTo(cbse().curriculumId());

        inChainDo(jdbc -> jdbc.update("DELETE FROM section WHERE id = ?", sectionId));
    }

    @Test @Tag("P1")
    void cert_ACAD_05_cbseAndCambridgeSectionsCoexistWithDistinctStrategies() {
        UUID cbseSection = currentFocusSection(cbse());
        UUID cieSection = currentFocusSection(cie());

        assertThat(queryOne("SELECT strategy_code FROM section WHERE id = ?", String.class, cbseSection))
            .isEqualTo("CBSE-CCE-2024");
        assertThat(queryOne("SELECT strategy_code FROM section WHERE id = ?", String.class, cieSection))
            .isEqualTo("CIE-IGCSE");

        // Assessment behaviour is dispatched off the section's strategy, not a global setting.
        var cbseAssessments = get("/v1/assessment?sectionId=" + cbseSection, principalToken(cbse())).getBody();
        var cieAssessments = get("/v1/assessment?sectionId=" + cieSection, principalToken(cie())).getBody();
        assertThat(cbseAssessments).isNotEmpty();
        assertThat(cieAssessments).isNotEmpty();
        assertThat(cbseAssessments.get(0).get("strategyCode").asText()).isEqualTo("CBSE-CCE-2024");
        assertThat(cieAssessments.get(0).get("strategyCode").asText()).isEqualTo("CIE-IGCSE");
    }

    @Test @Tag("P1")
    void cert_ACAD_06_capacityBlocksOrFlagsTheOverCapacityEnrolment() {
        String token = principalToken(cbse());
        UUID sectionId = UUID.fromString(post("/v1/tenancy/schools/" + cbse().id() + "/sections", body(
            "gradeId", gradeOf(cbse(), cbse().focusGradeCode()), "academicYearId", cbse().currentAy().id(),
            "code", "CAP", "name", "Grade 5-CAP", "strategyCode", cbse().strategyCode(),
            "capacity", 2), token).getBody().get("id").asText());

        UUID first = createStudent();
        UUID second = createStudent();
        UUID third = createStudent();
        try {
            assertThat(enrol(first, sectionId, null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(enrol(second, sectionId, null).getStatusCode()).isEqualTo(HttpStatus.OK);

            var full = enrol(third, sectionId, null);
            assertThat(full.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(full.getBody().get("message").asText()).contains("Section is full");
            assertThat(count("SELECT count(*) FROM enrolment WHERE section_id = ? AND status = 'active'",
                sectionId)).isEqualTo(2);

            // The override is allowed, but it has to say why — and the reason
            // stays on the enrolment.
            var overridden = enrol(third, sectionId, "Sibling of an existing student; principal approved");
            assertThat(overridden.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(queryOne("SELECT over_capacity_reason FROM enrolment WHERE student_id = ?",
                String.class, third)).contains("principal approved");
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM enrolment WHERE section_id = ?", sectionId);
                jdbc.update("DELETE FROM student WHERE id IN (?, ?, ?)", first, second, third);
                jdbc.update("DELETE FROM number_series WHERE scope_id = ?", sectionId);
                jdbc.update("DELETE FROM section WHERE id = ?", sectionId);
            });
        }
    }

    @Test @Tag("P2")
    @Disabled("No setup-readiness surface: section_subject_teacher records a primary teacher, but "
        + "nothing reports sections missing one before term start. New gap found in Phase 0.")
    void cert_ACAD_07_sectionWithoutPrimaryTeacherIsSurfacedAsSetupWarning() {
    }

    @Test @Tag("P2")
    void cert_ACAD_08_curriculumIsVersionedAndAMidYearEditDoesNotInvalidateDeliveredPlans() {
        String token = principalToken(cbse());

        var v1 = post("/v1/curriculum", Map.of("schoolId", cbse().id(), "boardCode", "CBSE",
            "strategyCode", cbse().strategyCode(), "name", "Grade 5 Science", "version", "1.0",
            "gradeId", gradeOf(cbse(), "5"), "subjectId", subjectOf(cbse(), "SCI")), token);
        UUID v1Id = UUID.fromString(v1.getBody().get("id").asText());

        var node = post("/v1/curriculum/" + v1Id + "/nodes",
            Map.of("nodeType", "unit", "code", "U1", "name", "Living things", "sortOrder", 1), token);
        assertThat(node.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID nodeId = UUID.fromString(node.getBody().get("id").asText());

        var plan = post("/v1/lms/lesson-plans", body(
            "schoolId", cbse().id(), "sectionId", currentFocusSection(cbse()),
            "subjectId", subjectOf(cbse(), "SCI"), "curriculumNodeId", nodeId,
            "title", "Living things — lesson 1", "plannedFor", "2026-07-06",
            "createdByStaffId", cbse().teacherStaffIds().get(0)), token);
        assertThat(plan.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID planId = UUID.fromString(plan.getBody().get("id").asText());
        post("/v1/lms/lesson-plans/" + planId + "/status", Map.of("status", "delivered"), token);

        // A mid-year revision lands as a new version; the delivered plan still points at v1's node.
        var v2 = post("/v1/curriculum", Map.of("schoolId", cbse().id(), "boardCode", "CBSE",
            "strategyCode", cbse().strategyCode(), "name", "Grade 5 Science", "version", "2.0",
            "gradeId", gradeOf(cbse(), "5"), "subjectId", subjectOf(cbse(), "SCI")), token);
        assertThat(v2.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(queryOne("SELECT curriculum_node_id FROM lesson_plan WHERE id = ?", UUID.class, planId))
            .isEqualTo(nodeId);
        assertThat(queryOne("SELECT status FROM lesson_plan WHERE id = ?", String.class, planId))
            .isEqualTo("delivered");
    }

    @Test @Tag("P1")
    void cert_ACAD_09_studentElectiveSubjectSetDrivesMarksTimetableAndReportCard() {
        String token = principalToken(cie());
        var block = createElectiveBlock(cie(), "ACAD09");
        try {
            // Each student's subject set carries their own option, not both.
            var setA = get("/v1/enrolment/" + block.enrolmentA() + "/subjects", token).getBody();
            var setB = get("/v1/enrolment/" + block.enrolmentB() + "/subjects", token).getBody();
            assertThat(subjectIdsOf(setA)).contains(block.subjectA().toString())
                .doesNotContain(block.subjectB().toString());
            assertThat(subjectIdsOf(setB)).contains(block.subjectB().toString())
                .doesNotContain(block.subjectA().toString());
            // The section's compulsory subjects are in both.
            assertThat(subjectIdsOf(setA)).contains(subjectOf(cie(), cie().subjectCodes().get(0)).toString());

            // The timetable follows the same rule: an option period appears only
            // for the student who takes it.
            post("/v1/timetable/slots", body(
                "sectionId", block.sectionId(), "subjectId", block.subjectA(),
                "teacherStaffId", cie().teacherStaffIds().get(0), "dayOfWeek", 4, "periodNo", 7,
                "startsAt", "14:00:00", "endsAt", "14:45:00", "room", "ACAD09-A",
                "effectiveFrom", cie().currentAy().startsOn().toString()), token);
            post("/v1/timetable/slots", body(
                "sectionId", block.sectionId(), "subjectId", block.subjectB(),
                "teacherStaffId", cie().teacherStaffIds().get(1), "dayOfWeek", 4, "periodNo", 7,
                "startsAt", "14:00:00", "endsAt", "14:45:00", "room", "ACAD09-B",
                "effectiveFrom", cie().currentAy().startsOn().toString()), token);

            var weekA = get("/v1/timetable/students/" + block.studentA(), token).getBody();
            assertThat(subjectIdsOf(weekA)).contains(block.subjectA().toString())
                .doesNotContain(block.subjectB().toString());

            // And marks: one for a subject the student does not take is refused
            // rather than quietly stored against them.
            UUID assessmentId = UUID.fromString(post("/v1/assessment", body(
                "schoolId", cie().id(), "sectionId", block.sectionId(), "subjectId", block.subjectB(),
                "termId", termOf(cie(), cie().currentAy().code(), "T1"),
                "strategyCode", cie().strategyCode(), "name", "Option B paper",
                "assessmentType", "Component", "maxMarks", 50.0, "weightPct", 100.0,
                "scheduledOn", "2026-07-15"), token).getBody().get("id").asText());
            UUID componentId = UUID.fromString(post("/v1/assessment/" + assessmentId + "/components", body(
                "code", "PAPER", "name", "Paper", "maxMarks", 50.0, "weightPct", 100.0,
                "sortOrder", 1), token).getBody().get("id").asText());

            var wrongStudent = post("/v1/assessment/components/" + componentId + "/marks", body(
                "schoolId", cie().id(), "studentId", block.studentA(), "rawMarks", 40.0,
                "enteredByStaffId", cie().teacherStaffIds().get(1)), token);
            assertThat(wrongStudent.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(wrongStudent.getBody().get("message").asText()).contains("does not take subject");

            var rightStudent = post("/v1/assessment/components/" + componentId + "/marks", body(
                "schoolId", cie().id(), "studentId", block.studentB(), "rawMarks", 40.0,
                "enteredByStaffId", cie().teacherStaffIds().get(1)), token);
            assertThat(rightStudent.getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            deleteElectiveBlock(block);
        }
    }

    @Test @Tag("P1")
    @Disabled("GAP-01 — no working-day pattern or calendar master, so nothing computes a working-day "
        + "denominator or shifts a due date (Phase 1).")
    void cert_ACAD_10_workingDayPatternIsHonouredEverywhere() {
    }

    // ---------------------------------------------------------------- helpers

    private java.util.List<String> subjectIdsOf(com.fasterxml.jackson.databind.JsonNode rows) {
        return rows.findValuesAsText("subjectId");
    }

    private UUID createStudent() {
        var created = post("/v1/people/students", body(
            "schoolId", cbse().id(), "firstName", "Capacity", "lastName", "Candidate",
            "dob", "2015-03-03", "gender", "female"), principalToken(cbse()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }

    private org.springframework.http.ResponseEntity<com.fasterxml.jackson.databind.JsonNode> enrol(
        UUID studentId, UUID sectionId, String overCapacityReason
    ) {
        return post("/v1/enrolment", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "academicYearId", cbse().currentAy().id(), "startsOn", "2026-08-01",
            "overCapacityReason", overCapacityReason), principalToken(cbse()));
    }
}
