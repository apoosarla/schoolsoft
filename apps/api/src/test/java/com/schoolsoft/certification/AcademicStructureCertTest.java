package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
    @Disabled("GAP-25 — no AY/term date validation: a term outside its academic year, or overlapping "
        + "terms, are both accepted (Phase 1).")
    void cert_ACAD_02_termOutsideTheYearOrOverlappingTermsAreRejected() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-25 — academic years may overlap; no EXCLUDE constraint on daterange (Phase 1).")
    void cert_ACAD_03_overlappingAcademicYearsAreRejected() {
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
    @Disabled("GAP-10 — section.capacity is stored but never checked at enrolment or admission offer, "
        + "and there is no over-capacity override with a reason (Phase 2).")
    void cert_ACAD_06_capacityBlocksOrFlagsTheOverCapacityEnrolment() {
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
    @Disabled("GAP-05 — subjects bind to sections only; there is no student_subject election, so marks, "
        + "timetable and report cards cannot follow a student's own subject set (Phase 2).")
    void cert_ACAD_09_studentElectiveSubjectSetDrivesMarksTimetableAndReportCard() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-01 — no working-day pattern or calendar master, so nothing computes a working-day "
        + "denominator or shifts a due date (Phase 1).")
    void cert_ACAD_10_workingDayPatternIsHonouredEverywhere() {
    }
}
