package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The report card's lifecycle as a state machine: draft → locked → published,
 * and back to draft only by an explicit unlock.
 *
 * <p>Each transition is one conditional UPDATE that asserts the state it moves
 * out of. Read-the-status-then-write leaves a window between the two, and every
 * one of these tests is a thing that window allowed:</p>
 *
 * <ul>
 *   <li>{@code lock} on a published card matched no row and reported success
 *       anyway — the caller believed they had locked it.</li>
 *   <li>{@code publish} checked for draft and then wrote unconditionally, so a
 *       card unlocked in between was published regardless — a family shown a
 *       card the school had taken back.</li>
 * </ul>
 *
 * <p>Serial tests cannot open the window, so what these assert is the property
 * that closes it: a transition from the wrong state is refused with a 409
 * rather than silently doing nothing.</p>
 */
@Tag("harness")
class ReportCardTransitionTest extends AbstractCertificationTest {

    @Test
    @DisplayName("a draft cannot be published without being locked first")
    void publishingADraftIsRefused() {
        UUID card = draftCard("CERT-RCT-01");
        try {
            var refused = post("/v1/assessment/report-cards/" + card + "/publish", null, staff());

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(status(card)).isEqualTo("draft");
        } finally {
            deleteCard(card);
        }
    }

    @Test
    @DisplayName("the happy path walks draft to locked to published")
    void theLifecycleRuns() {
        UUID card = draftCard("CERT-RCT-02");
        try {
            assertThat(status(card)).isEqualTo("draft");

            assertThat(post("/v1/assessment/report-cards/" + card + "/lock", null, staff())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(status(card)).isEqualTo("locked");

            assertThat(post("/v1/assessment/report-cards/" + card + "/publish", null, staff())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(status(card)).isEqualTo("published");
        } finally {
            deleteCard(card);
        }
    }

    /**
     * A retried request must not fail because the first attempt worked. The
     * transition is refused when it would change the wrong state, not when it
     * has nothing left to do.
     */
    @Test
    @DisplayName("re-running a transition that already happened succeeds")
    void transitionsAreIdempotent() {
        UUID card = draftCard("CERT-RCT-03");
        try {
            post("/v1/assessment/report-cards/" + card + "/lock", null, staff());

            assertThat(post("/v1/assessment/report-cards/" + card + "/lock", null, staff())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(status(card)).isEqualTo("locked");

            post("/v1/assessment/report-cards/" + card + "/publish", null, staff());
            assertThat(post("/v1/assessment/report-cards/" + card + "/publish", null, staff())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(status(card)).isEqualTo("published");
        } finally {
            deleteCard(card);
        }
    }

    /**
     * The one that used to pass quietly. `lock` guarded on
     * {@code status <> 'published'}, so against a published card it updated
     * nothing, threw nothing, and returned the card — which reads to the
     * caller as "locked".
     */
    @Test
    @DisplayName("locking a published card is refused rather than ignored")
    void lockingAPublishedCardIsRefused() {
        UUID card = draftCard("CERT-RCT-04");
        try {
            post("/v1/assessment/report-cards/" + card + "/lock", null, staff());
            post("/v1/assessment/report-cards/" + card + "/publish", null, staff());

            var refused = post("/v1/assessment/report-cards/" + card + "/lock", null, staff());

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody().get("message").asText()).contains("published");
            assertThat(status(card)).isEqualTo("published");
        } finally {
            deleteCard(card);
        }
    }

    /**
     * Unlocking is idempotent for the same reason locking is: its job is to
     * leave the card in draft, and a card already in draft has had that done
     * to it. Refusing here would make a retried unlock fail because the first
     * one worked.
     */
    @Test
    @DisplayName("unlocking returns a published card to draft, and says so again if asked twice")
    void unlockReturnsACardToDraft() {
        UUID card = draftCard("CERT-RCT-05");
        try {
            post("/v1/assessment/report-cards/" + card + "/lock", null, staff());
            post("/v1/assessment/report-cards/" + card + "/publish", null, staff());

            var unlocked = post("/v1/assessment/report-cards/" + card + "/unlock",
                body("reason", "the science mark was wrong"), staff());
            assertThat(unlocked.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(status(card)).isEqualTo("draft");

            var again = post("/v1/assessment/report-cards/" + card + "/unlock",
                body("reason", "again"), staff());
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(status(card)).isEqualTo("draft");
        } finally {
            deleteCard(card);
        }
    }

    // ===== helpers =====

    private String staff() { return principalToken(cbse()); }

    private String status(UUID cardId) {
        return queryOne("SELECT status FROM report_card WHERE id = ?", String.class, cardId);
    }

    private UUID draftCard(String templateCode) {
        return UUID.fromString(post("/v1/assessment/report-cards", body(
            "schoolId", cbse().id(),
            "studentId", firstStudentIn(currentFocusSection(cbse())),
            "academicYearId", cbse().currentAy().id(),
            "termId", termOf(cbse(), cbse().currentAy().code(), "T1"),
            "strategyCode", cbse().strategyCode(),
            "templateCode", templateCode,
            "payload", Map.of("headline", "Transition test"),
            "coScholastic", List.of()), staff()).getBody().get("id").asText());
    }

    private void deleteCard(UUID cardId) {
        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM report_card_subject WHERE report_card_id = ?", cardId);
            jdbc.update("DELETE FROM report_card_coscholastic WHERE report_card_id = ?", cardId);
            jdbc.update("DELETE FROM report_card WHERE id = ?", cardId);
        });
    }
}
