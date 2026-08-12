package com.schoolsoft.certification.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Guards the harness itself, not the product: if this fails, no scenario result
 * below it means anything.
 */
class FixtureSmokeTest extends AbstractCertificationTest {

    @Test @Tag("harness")
    void twoSchoolsWithPriorYearHistoryAreSeeded() {
        assertThat(cbse().boardCode()).isEqualTo("CBSE");
        assertThat(cie().boardCode()).isEqualTo("CIE");

        assertThat(count("SELECT count(*) FROM school")).isEqualTo(2);
        // Scoped to fixture-seeded admission numbers: scenarios add their own students
        // to the same chain, and this assertion is about the seed, not the run's residue.
        assertThat(count("SELECT count(*) FROM enrolment e JOIN student s ON s.id = e.student_id "
            + "WHERE e.academic_year_id = ? AND e.status = 'active' AND s.admission_no LIKE 'ADM%'",
            cbse().currentAy().id())).isEqualTo(240);
        assertThat(count("SELECT count(*) FROM enrolment WHERE academic_year_id = ? AND status = 'promoted'",
            cbse().priorAy().id())).isEqualTo(220);

        // A full prior year of history on the focus section.
        UUID priorSection = priorFocusSection(cbse());
        assertThat(count("SELECT count(*) FROM attendance_record WHERE section_id = ?", priorSection))
            .isGreaterThan(200);
        assertThat(count("SELECT count(*) FROM report_card WHERE academic_year_id = ?", cbse().priorAy().id()))
            .isEqualTo(10);
        assertThat(count("SELECT count(*) FROM section_subject_teacher sst " +
            "JOIN section s ON s.id = sst.section_id WHERE s.school_id = ?", cbse().id()))
            .isGreaterThan(0);
    }

    @Test @Tag("harness")
    void seededStaffTokenReachesTheApi() {
        var response = get("/v1/tenancy/schools", principalToken(cbse()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isArray()).isTrue();
    }
}
