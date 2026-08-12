package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-LIB — library. */
class LibraryCertTest extends AbstractCertificationTest {

    @Test @Tag("P2")
    void cert_LIB_01_copyIsIssuedWithADueDateAndTheReturnClosesTheIssue() {
        String token = librarianToken(cbse());
        var title = post("/v1/library/titles?schoolId=" + cbse().id(), Map.of(
            "isbn", "978" + UUID.randomUUID().toString().substring(0, 10),
            "title", "A Certification Reader", "author", "Test Author",
            "publisher", "Cert Press", "year", 2026), token);
        assertThat(title.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID titleId = UUID.fromString(title.getBody().get("id").asText());

        var copy = post("/v1/library/titles/" + titleId + "/copies",
            Map.of("barcode", "CERT-" + UUID.randomUUID().toString().substring(0, 8)), token);
        UUID copyId = UUID.fromString(copy.getBody().get("id").asText());
        assertThat(copy.getBody().get("status").asText()).isEqualTo("available");

        UUID studentId = studentsIn(currentFocusSection(cbse())).get(2);
        var issued = post("/v1/library/issues", Map.of("schoolId", cbse().id(), "copyId", copyId,
            "memberType", "student", "memberId", studentId, "dueOn", "2026-08-26"), token);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID issueId = UUID.fromString(issued.getBody().get("id").asText());
        assertThat(queryOne("SELECT status FROM library_copy WHERE id = ?", String.class, copyId))
            .isEqualTo("issued");
        assertThat(get("/v1/library/issues/active?memberType=student&memberId=" + studentId, token).getBody())
            .isNotEmpty();

        var returned = post("/v1/library/issues/" + issueId + "/return", null, token);
        assertThat(returned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(returned.getBody().get("returnedOn").asText()).isNotBlank();
        assertThat(queryOne("SELECT status FROM library_copy WHERE id = ?", String.class, copyId))
            .isEqualTo("available");
    }

    @Test @Tag("P3")
    @Disabled("GAP-22 — no per-grade issue limits (Phase 4 posts library charges to the ledger; limits "
        + "ride along with it).")
    void cert_LIB_02_perGradeIssueLimitsAreEnforced() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-22 — library_issue.fine is a column nothing computes, and no fine posts to the fee "
        + "ledger (Phase 4).")
    void cert_LIB_03_overdueFinePostsToTheStudentsFeeLedger() {
    }

    @Test @Tag("P3")
    @Disabled("GAP-22 — no lost/damaged charge path; the copy status exists but no charge is raised "
        + "(Phase 4).")
    void cert_LIB_04_lostCopyIsChargedAndWithdrawnFromCirculation() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-03 + GAP-22 — no year-end clearance workflow to block a student with an unreturned "
        + "copy (Phase 7).")
    void cert_LIB_05_yearEndClearanceBlocksAnUnreturnedCopy() {
    }
}
