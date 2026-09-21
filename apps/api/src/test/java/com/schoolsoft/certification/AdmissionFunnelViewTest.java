package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The pipeline screen opens on counts and fetches rows a stage at a time.
 *
 * <p>The thing being pinned is that it can: that the counts are a query rather
 * than a length, that a page of one stage is a page rather than the whole
 * stage, and that the two agree. A season is four hundred applications and a
 * closed year several thousand, so a board that reads them all to draw a dozen
 * numbers stops working in the second year of use.</p>
 */
@Tag("harness")
class AdmissionFunnelViewTest extends AbstractCertificationTest {

    private UUID lodge(String token, String who) {
        return UUID.fromString(post("/v1/admissions/applications", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
            "gradeId", gradeOf(cbse(), "1"),
            "applicantFirstName", who, "applicantLastName", "Counted",
            "guardianName", "Counted Guardian", "guardianPhone", "919000000079",
            "source", "walkin"), token).getBody().get("id").asText());
    }

    private long countFor(com.fasterxml.jackson.databind.JsonNode summary, String state) {
        for (var row : summary.get("byState")) {
            if (row.get("state").asText().equals(state)) return row.get("count").asLong();
        }
        throw new AssertionError("summary has no row for '" + state + "'");
    }

    @Test
    @DisplayName("the summary counts every stage and agrees with the rows behind it")
    void theSummaryCountsWithoutListing() {
        String token = registrarToken(cbse());

        var before = get("/v1/admissions/summary?schoolId=" + cbse().id(), token);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);
        long leadsBefore = countFor(before.getBody(), "lead");
        long totalBefore = before.getBody().get("total").asLong();

        lodge(token, "Freshly");

        var after = get("/v1/admissions/summary?schoolId=" + cbse().id(), token).getBody();
        assertThat(countFor(after, "lead")).isEqualTo(leadsBefore + 1);
        assertThat(after.get("total").asLong()).isEqualTo(totalBefore + 1);

        // Every state the funnel has gets a row, including the empty ones: a
        // lane that vanishes when it empties is harder to read than a zero.
        List<String> states = new ArrayList<>();
        after.get("byState").forEach(row -> states.add(row.get("state").asText()));
        assertThat(states).containsExactlyInAnyOrder(
            "lead", "application_started", "document_pending", "fee_pending", "review",
            "test_scheduled", "test_done", "offered", "accepted", "waitlist",
            "enrolled", "rejected", "lapsed");

        // The total is the sum of the parts, so the tiles always add up to the
        // number in the header.
        long summed = 0;
        for (var row : after.get("byState")) summed += row.get("count").asLong();
        assertThat(summed).isEqualTo(after.get("total").asLong());

        // And the count for a stage is what a full read of that stage returns.
        var allLeads = get("/v1/admissions/applications?schoolId=" + cbse().id() + "&state=lead", token).getBody();
        assertThat((long) allLeads.size()).isEqualTo(countFor(after, "lead"));
    }

    @Test
    @DisplayName("a stage is read a page at a time, and the pages do not overlap")
    void oneStageIsPaged() {
        String token = registrarToken(cbse());
        for (int i = 0; i < 3; i++) lodge(token, "Paged" + i);

        var firstPage = get("/v1/admissions/applications?schoolId=" + cbse().id()
            + "&state=lead&limit=2&offset=0", token);
        assertThat(firstPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstPage.getBody().size()).isEqualTo(2);

        var secondPage = get("/v1/admissions/applications?schoolId=" + cbse().id()
            + "&state=lead&limit=2&offset=2", token).getBody();
        assertThat(secondPage.size()).isEqualTo(2);

        List<String> ids = new ArrayList<>();
        firstPage.getBody().forEach(node -> ids.add(node.get("id").asText()));
        List<String> laterIds = new ArrayList<>();
        secondPage.forEach(node -> laterIds.add(node.get("id").asText()));

        // The order is total — created_at with id as the tie-break — so nothing
        // appears on two pages and nothing falls between them.
        assertThat(ids).doesNotContainAnyElementsOf(laterIds);

        // Asking for no limit still returns the stage, which is what an export
        // needs and what the older callers already do.
        var everything = get("/v1/admissions/applications?schoolId=" + cbse().id() + "&state=lead", token).getBody();
        assertThat(everything.size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("the screen asks once per stage which moves exist, not once per row")
    void movesAreAnsweredPerStage() {
        String token = registrarToken(cbse());
        var moves = get("/v1/admissions/moves?schoolId=" + cbse().id() + "&fromState=lead", token);
        assertThat(moves.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> allowed = new ArrayList<>();
        moves.getBody().forEach(node -> allowed.add(node.asText()));
        assertThat(allowed).containsExactlyInAnyOrder("application_started", "rejected", "lapsed");

        // It is the same answer the per-application endpoint gives, which is
        // what makes asking once per stage safe.
        UUID id = lodge(token, "Mover");
        List<String> perApplication = new ArrayList<>();
        get("/v1/admissions/applications/" + id + "/moves", token).getBody()
            .forEach(node -> perApplication.add(node.asText()));
        assertThat(perApplication).containsExactlyInAnyOrderElementsOf(allowed);

        // Nothing follows a terminal stage, so the row offers no buttons at all.
        List<String> fromEnrolled = new ArrayList<>();
        get("/v1/admissions/moves?schoolId=" + cbse().id() + "&fromState=enrolled", token).getBody()
            .forEach(node -> fromEnrolled.add(node.asText()));
        assertThat(fromEnrolled).isEmpty();
    }

    @Test
    @DisplayName("search finds one child by the things a parent would quote")
    void searchFindsOneChild() {
        String token = registrarToken(cbse());
        String surname = "Searchable" + UUID.randomUUID().toString().substring(0, 6);

        var created = post("/v1/admissions/applications", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
            "gradeId", gradeOf(cbse(), "1"),
            "applicantFirstName", "Findme", "applicantLastName", surname,
            "applicantDob", "2019-04-17", "applicantGender", "female",
            "guardianName", "Findme Guardian", "guardianPhone", "919000000081",
            "source", "referral"), token).getBody();
        String applicationNo = created.get("applicationNo").asText();

        // The one box takes whatever the family has to hand.
        for (String needle : List.of(surname, "Findme " + surname, applicationNo, "919000000081")) {
            var hit = get("/v1/admissions/applications/search?schoolId=" + cbse().id() + "&q=" + needle, token);
            assertThat(hit.getStatusCode()).as("searching for " + needle).isEqualTo(HttpStatus.OK);
            assertThat(hit.getBody().get("total").asLong()).as("matches for " + needle).isGreaterThanOrEqualTo(1);
            List<String> numbers = new ArrayList<>();
            hit.getBody().get("rows").forEach(row -> numbers.add(row.get("applicationNo").asText()));
            assertThat(numbers).contains(applicationNo);
        }

        // Case does not matter: nobody types a surname the way the form stored it.
        assertThat(get("/v1/admissions/applications/search?schoolId=" + cbse().id()
            + "&q=" + surname.toLowerCase(), token).getBody().get("total").asLong()).isGreaterThanOrEqualTo(1);

        // The result carries the status, which is the question being asked.
        var one = get("/v1/admissions/applications/search?schoolId=" + cbse().id()
            + "&applicationNo=" + applicationNo, token).getBody();
        assertThat(one.get("total").asLong()).isEqualTo(1);
        assertThat(one.get("rows").get(0).get("state").asText()).isEqualTo("lead");
    }

    @Test
    @DisplayName("each extra field narrows the search, and an empty search is refused")
    void advancedSearchNarrows() {
        String token = registrarToken(cbse());
        String surname = "Narrowing" + UUID.randomUUID().toString().substring(0, 6);

        // Two children, same surname, different dates of birth -- the case the
        // advanced panel exists for.
        for (String dob : List.of("2018-01-09", "2020-11-23")) {
            post("/v1/admissions/applications", body(
                "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
                "gradeId", gradeOf(cbse(), "1"),
                "applicantFirstName", "Twin", "applicantLastName", surname,
                "applicantDob", dob,
                "guardianName", "Shared Guardian", "guardianPhone", "919000000082",
                "source", "walkin"), token);
        }

        String byName = "/v1/admissions/applications/search?schoolId=" + cbse().id() + "&name=" + surname;
        assertThat(get(byName, token).getBody().get("total").asLong()).isEqualTo(2);

        // Adding the date of birth picks one of them out.
        var narrowed = get(byName + "&dob=2020-11-23", token).getBody();
        assertThat(narrowed.get("total").asLong()).isEqualTo(1);
        assertThat(narrowed.get("rows").get(0).get("applicantDob").asText()).isEqualTo("2020-11-23");

        // Fields combine with AND, so a contradiction finds nothing rather than
        // falling back to the wider match.
        assertThat(get(byName + "&dob=1999-01-01", token).getBody().get("total").asLong()).isZero();

        // A search with nothing to go on is refused: it is the whole table
        // wearing a question's clothes.
        assertThat(get("/v1/admissions/applications/search?schoolId=" + cbse().id(), token).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("every stage's moves come back in one answer for a list that spans stages")
    void allMovesComeBackTogether() {
        String token = registrarToken(cbse());
        var all = get("/v1/admissions/moves/all?schoolId=" + cbse().id(), token);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Every state is a key, so a search result in any state finds its moves.
        assertThat(all.getBody().has("lead")).isTrue();
        assertThat(all.getBody().has("enrolled")).isTrue();

        List<String> fromLead = new ArrayList<>();
        all.getBody().get("lead").forEach(node -> fromLead.add(node.asText()));
        assertThat(fromLead).containsExactlyInAnyOrder("application_started", "rejected", "lapsed");

        // And it agrees with the per-stage endpoint the stage view uses.
        List<String> perStage = new ArrayList<>();
        get("/v1/admissions/moves?schoolId=" + cbse().id() + "&fromState=lead", token).getBody()
            .forEach(node -> perStage.add(node.asText()));
        assertThat(perStage).containsExactlyInAnyOrderElementsOf(fromLead);

        assertThat(all.getBody().get("enrolled")).isEmpty();
    }

    @Test
    @DisplayName("the summary names the offers that are about to lapse")
    void expiringOffersAreCounted() {
        String token = registrarToken(cbse());
        UUID id = lodge(token, "Expiring");
        for (String state : List.of("application_started", "document_pending", "fee_pending", "review",
                "test_scheduled", "test_done")) {
            post("/v1/admissions/applications/" + id + "/transition", Map.of("toState", state), token);
        }

        long expiredBefore = get("/v1/admissions/summary?schoolId=" + cbse().id(), token)
            .getBody().get("offersExpired").asLong();

        // An offer made with a date already behind us: the seat is held for
        // somebody who has stopped answering.
        assertThat(post("/v1/admissions/applications/" + id + "/transition",
            body("toState", "offered", "offerExpiresOn", java.time.LocalDate.now().minusDays(1).toString()),
            token).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(get("/v1/admissions/summary?schoolId=" + cbse().id(), token)
            .getBody().get("offersExpired").asLong()).isEqualTo(expiredBefore + 1);
    }
}
