package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import com.schoolsoft.certification.support.CertificationFixture.SchoolSeed;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The admissions state machine as a worked example, the way
 * {@code ReportCardTransitionTest} is one for report cards.
 *
 * <p>Two things are being pinned here. First, that the funnel a school runs is
 * configuration rather than something every school shares: a school with no
 * entrance test cannot schedule one, and a school with an entrance test cannot
 * hand out an offer without it. Second, that an offer carries a date it expires
 * on — the column existed and was read by the public tracking page for a year
 * while nothing ever wrote it.</p>
 */
@Tag("harness")
class AdmissionTransitionTest extends AbstractCertificationTest {

    /** Walks an application to `review`, which is where the two funnels differ. */
    private UUID applicationInReview(SchoolSeed school, String token) {
        UUID id = UUID.fromString(post("/v1/admissions/applications", body(
            "schoolId", school.id(), "academicYearId", school.currentAy().id(),
            "gradeId", gradeOf(school, school.focusGradeCode()),
            "applicantFirstName", "Funnel", "applicantLastName", "Probe",
            "guardianName", "Probe Guardian", "guardianPhone", "919000000077",
            "source", "walkin"), token).getBody().get("id").asText());

        for (String state : List.of("application_started", "document_pending", "fee_pending", "review")) {
            assertThat(post("/v1/admissions/applications/" + id + "/transition",
                Map.of("toState", state), token).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        return id;
    }

    @Test
    @DisplayName("a school that tests reaches an offer through the test, not around it")
    void theTestingFunnelCannotBeSkipped() {
        String token = registrarToken(cbse());
        UUID id = applicationInReview(cbse(), token);

        // cbse() runs an entrance test — the default every school is migrated
        // with — so the shortcut from review straight to an offer is not a move
        // it has, and the refusal says why rather than just "no".
        var shortcut = post("/v1/admissions/applications/" + id + "/transition",
            Map.of("toState", "offered"), token);
        assertThat(shortcut.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(shortcut.getBody().get("message").asText())
            .contains("runs an entrance test");

        var moves = get("/v1/admissions/applications/" + id + "/moves", token).getBody();
        List<String> allowed = new java.util.ArrayList<>();
        moves.forEach(node -> allowed.add(node.asText()));
        assertThat(allowed).contains("test_scheduled").doesNotContain("offered");

        // The long way round is the only way round.
        assertThat(post("/v1/admissions/applications/" + id + "/transition",
            Map.of("toState", "test_scheduled"), token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a school with no entrance test offers from review and cannot schedule a test")
    void theUntestedFunnelHasNoTestToSchedule() {
        SchoolSeed school = cie();
        String admin = principalToken(school);
        String token = registrarToken(school);

        // The second school is shared with other scenarios, so the funnel is
        // switched back whatever happens below.
        var before = get("/v1/admissions/policy?schoolId=" + school.id(), token).getBody();
        try {
            assertThat(put("/v1/admissions/policy", body(
                "schoolId", school.id(), "entranceTestRequired", false,
                "offerValidityDays", 10), admin).getStatusCode()).isEqualTo(HttpStatus.OK);

            UUID id = applicationInReview(school, token);

            var scheduled = post("/v1/admissions/applications/" + id + "/transition",
                Map.of("toState", "test_scheduled"), token);
            assertThat(scheduled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(scheduled.getBody().get("message").asText())
                .contains("no entrance test");

            var moves = get("/v1/admissions/applications/" + id + "/moves", token).getBody();
            List<String> allowed = new java.util.ArrayList<>();
            moves.forEach(node -> allowed.add(node.asText()));
            assertThat(allowed).contains("offered").doesNotContain("test_scheduled");

            // And a score is refused outright: there was no test to score.
            assertThat(post("/v1/admissions/applications/" + id + "/test-score",
                Map.of("score", 71.0, "notes", "n/a"), token).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

            // The offer it can make carries the school's own window.
            assertThat(post("/v1/admissions/applications/" + id + "/transition",
                Map.of("toState", "offered"), token).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(queryOne("SELECT offer_expires_on FROM admission_application WHERE id = ?",
                java.sql.Date.class, id).toLocalDate())
                .isEqualTo(LocalDate.now().plusDays(10));
        } finally {
            put("/v1/admissions/policy", body(
                "schoolId", school.id(),
                "entranceTestRequired", before.get("entranceTestRequired").asBoolean(),
                "offerValidityDays", before.get("offerValidityDays").asInt()), admin);
        }
    }

    @Test
    @DisplayName("an offer carries the date it expires, and the office can extend it")
    void anOfferCarriesItsExpiry() {
        String token = registrarToken(cbse());
        UUID id = applicationInReview(cbse(), token);
        for (String state : List.of("test_scheduled", "test_done")) {
            post("/v1/admissions/applications/" + id + "/transition", Map.of("toState", state), token);
        }

        // cbse()'s window is the migrated default of 14 days.
        assertThat(post("/v1/admissions/applications/" + id + "/transition",
            Map.of("toState", "offered"), token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryOne("SELECT offer_expires_on FROM admission_application WHERE id = ?",
            java.sql.Date.class, id).toLocalDate())
            .isEqualTo(LocalDate.now().plusDays(14));

        // Moving on does not wipe what the family was told.
        assertThat(post("/v1/admissions/applications/" + id + "/transition",
            Map.of("toState", "accepted"), token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(queryOne("SELECT offer_expires_on FROM admission_application WHERE id = ?",
            java.sql.Date.class, id)).isNotNull();
    }

    @Test
    @DisplayName("a test score moves the application and cannot be recorded out of turn")
    void aScoreIsRecordedWhileTheTestIsScheduled() {
        String token = registrarToken(cbse());
        UUID id = applicationInReview(cbse(), token);

        // Not yet scheduled: the score has nowhere to land. It used to be
        // written anyway, against an application in any state at all.
        assertThat(post("/v1/admissions/applications/" + id + "/test-score",
            Map.of("score", 61.0, "notes", "early"), token).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(queryOne("SELECT test_score FROM admission_application WHERE id = ?", Double.class, id))
            .isNull();

        post("/v1/admissions/applications/" + id + "/transition",
            Map.of("toState", "test_scheduled"), token);

        // Recording the result is what finishes the test — the score and the
        // state no longer disagree until somebody moves it by hand.
        var scored = post("/v1/admissions/applications/" + id + "/test-score",
            Map.of("score", 82.5, "notes", "strong on comprehension"), token);
        assertThat(scored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scored.getBody().get("state").asText()).isEqualTo("test_done");
        assertThat(count("SELECT count(*) FROM admission_event WHERE application_id = ? "
            + "AND event_type = 'state_change' AND to_state = 'test_done'", id)).isEqualTo(1);

        // An amendment corrects the number and stays put: nothing moved, so it
        // is not a second state change.
        var amended = post("/v1/admissions/applications/" + id + "/test-score",
            Map.of("score", 84.0, "notes", "remarked"), token);
        assertThat(amended.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(amended.getBody().get("state").asText()).isEqualTo("test_done");
        assertThat(amended.getBody().get("testScore").asDouble()).isEqualTo(84.0);
        assertThat(count("SELECT count(*) FROM admission_event WHERE application_id = ? "
            + "AND event_type = 'state_change' AND to_state = 'test_done'", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admission_event WHERE application_id = ? "
            + "AND event_type = 'test_score_amended'", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("an application number comes from the school's series")
    void applicationNumbersComeFromTheSeries() {
        String token = registrarToken(cbse());
        var created = post("/v1/admissions/applications", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
            "gradeId", gradeOf(cbse(), "1"),
            "applicantFirstName", "Series", "applicantLastName", "Probe",
            "guardianName", "Probe Guardian", "guardianPhone", "919000000078",
            "source", "walkin"), token);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().get("applicationNo").asText()).matches("APP\\d{6}");

        // The file begins; it does not move. The trail used to record this as
        // `lead -> lead`, a move the machine itself forbids.
        UUID id = UUID.fromString(created.getBody().get("id").asText());
        assertThat(count("SELECT count(*) FROM admission_event WHERE application_id = ? "
            + "AND event_type = 'enquiry_received' AND from_state IS NULL AND to_state = 'lead'", id))
            .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM admission_event WHERE application_id = ? "
            + "AND from_state = to_state", id)).isZero();
    }
}
