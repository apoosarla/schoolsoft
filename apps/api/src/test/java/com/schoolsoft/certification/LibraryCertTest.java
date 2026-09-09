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
    void cert_LIB_03_overdueFinePostsToTheFeeLedger() {
        String token = librarianToken(cbse());
        UUID studentId = studentsIn(currentFocusSection(cbse())).get(4);
        UUID copyId = spareCopy(cbse());

        // Resolved before entering inChainDo: a nested tenant-scoped query would
        // clear the outer context on its way out.
        UUID tuitionHead = queryOne("SELECT id FROM fee_head WHERE school_id = ? AND code = 'TUITION'",
            UUID.class, cbse().id());
        inChainDo(jdbc -> jdbc.update(
            "INSERT INTO library_charge_policy (id, school_id, fine_per_day, fee_head_id) " +
            "VALUES (gen_random_uuid(), ?, 5, ?) ON CONFLICT (school_id) DO UPDATE SET fine_per_day = 5",
            cbse().id(), tuitionHead));

        double duesBefore = duesOf(studentId);
        var issued = post("/v1/library/issues", body(
            "schoolId", cbse().id(), "copyId", copyId, "memberType", "student", "memberId", studentId,
            "dueOn", java.time.LocalDate.now().minusDays(4).toString()), token);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID issueId = UUID.fromString(issued.getBody().get("id").asText());

        try {
            var returned = post("/v1/library/issues/" + issueId + "/return", null, token);
            assertThat(returned.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(returned.getBody().get("fine").asDouble()).isEqualTo(20.0);   // 4 days x 5

            // The fine is on the student's fee account, not just the issue row.
            assertThat(duesOf(studentId)).isEqualTo(duesBefore + 20.0);
            UUID adjustmentId = queryOne("SELECT fee_adjustment_id FROM library_issue WHERE id = ?",
                UUID.class, issueId);
            assertThat(adjustmentId).isNotNull();
            assertThat(count("SELECT count(*) FROM ledger_entry WHERE source_type = 'adjustment' " +
                "AND source_id = ?", adjustmentId)).isEqualTo(2);
        } finally {
            clearLibraryCharges(issueId, studentId);
        }
    }

    @Test @Tag("P3")
    void cert_LIB_04_lostOrDamagedCopyIsChargedAndWithdrawn() {
        String token = librarianToken(cbse());
        UUID studentId = studentsIn(currentFocusSection(cbse())).get(5);
        UUID copyId = spareCopy(cbse());
        inChainDo(jdbc -> jdbc.update("UPDATE library_title SET price = 600 WHERE id = " +
            "(SELECT title_id FROM library_copy WHERE id = ?)", copyId));

        double duesBefore = duesOf(studentId);
        UUID issueId = UUID.fromString(post("/v1/library/issues", body(
            "schoolId", cbse().id(), "copyId", copyId, "memberType", "student", "memberId", studentId,
            "dueOn", java.time.LocalDate.now().plusDays(7).toString()), token).getBody().get("id").asText());

        try {
            var charged = post("/v1/library/issues/" + issueId + "/charge",
                Map.of("kind", "lost", "notes", "Reported lost by the family"), token);
            assertThat(charged.getStatusCode()).isEqualTo(HttpStatus.OK);

            // Charged at the title's price, and out of circulation.
            assertThat(duesOf(studentId)).isEqualTo(duesBefore + 600.0);
            assertThat(queryOne("SELECT status FROM library_copy WHERE id = ?", String.class, copyId))
                .isEqualTo("lost");
            assertThat(queryOne("SELECT charge_kind FROM library_issue WHERE id = ?", String.class, issueId))
                .isEqualTo("lost");
        } finally {
            clearLibraryCharges(issueId, studentId);
            inChainDo(jdbc -> jdbc.update("UPDATE library_copy SET status = 'available' WHERE id = ?", copyId));
        }
    }

    @Test @Tag("P2")
    void cert_LIB_05_yearEndClearanceBlocksAnUnreturnedCopy() {
        String token = principalToken(cbse());
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        UUID studentId = UUID.fromString(post("/v1/people/students", body(
            "schoolId", cbse().id(), "admissionNo", "LIB05-" + suffix,
            "firstName", "LIB05", "lastName", "Borrower-" + suffix,
            "dob", "2015-01-01", "gender", "female"), token).getBody().get("id").asText());
        UUID enrolmentId = UUID.fromString(post("/v1/enrolment", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", currentFocusSection(cbse()),
            "academicYearId", cbse().currentAy().id(), "startsOn", "2026-04-01",
            "overCapacityReason", "Certification scenario LIB-05"), token).getBody().get("id").asText());

        String librarian = librarianToken(cbse());
        UUID titleId = UUID.fromString(post("/v1/library/titles?schoolId=" + cbse().id(), Map.of(
            "title", "The Long Loan", "author", "Certification", "isbn", "ISBN-LIB05-" + suffix), librarian)
            .getBody().get("id").asText());
        UUID copyId = UUID.fromString(post("/v1/library/titles/" + titleId + "/copies",
            Map.of("barcode", "BC-LIB05-" + suffix), librarian).getBody().get("id").asText());
        UUID issueId = UUID.fromString(post("/v1/library/issues", Map.of(
            "schoolId", cbse().id(), "copyId", copyId, "memberType", "student",
            // Not yet due: this scenario is about the copy being out, not about
            // the fine, and a late return would post a charge that blocks the
            // fees line the next probe looks at.
            "memberId", studentId, "dueOn",
            java.time.LocalDate.now().plusMonths(1).toString()), librarian).getBody().get("id").asText());

        // The child leaves at the end of the year with the book still out.
        var filed = post("/v1/enrolment/withdrawals", body(
            "enrolmentId", enrolmentId, "reasonCode", "graduation",
            "reason", "End of the year", "lastWorkingDate", "2026-08-31"), token);
        UUID withdrawalId = UUID.fromString(filed.getBody().get("id").asText());

        // The library line blocks — and says what is outstanding, not merely that
        // something is.
        var library = itemFor(filed.getBody(), "library");
        assertThat(library.get("state").asText()).isEqualTo("blocked");
        assertThat(library.get("detail").asText()).isEqualTo("1 copy not returned");

        for (String area : List.of("fees", "transport", "assets")) {
            post("/v1/enrolment/withdrawals/" + withdrawalId + "/clearance/" + area,
                body("reason", "Checked"), token);
        }
        assertThat(post("/v1/enrolment/withdrawals/" + withdrawalId + "/complete",
            body("reason", "Leaving"), token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Returning the copy clears it, and the school does not have to tick a
        // box the library disagrees with — the probe re-asks.
        post("/v1/library/issues/" + issueId + "/return", null, librarian);
        var refreshed = post("/v1/enrolment/withdrawals/" + withdrawalId + "/clearance/refresh", null, token);
        assertThat(itemFor(refreshed.getBody(), "library").get("state").asText()).isEqualTo("cleared");
        assertThat(refreshed.getBody().get("state").asText()).isEqualTo("cleared");

        assertThat(post("/v1/enrolment/withdrawals/" + withdrawalId + "/complete",
            body("reason", "Clearance complete"), token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private com.fasterxml.jackson.databind.JsonNode itemFor(
            com.fasterxml.jackson.databind.JsonNode withdrawal, String area) {
        for (var item : withdrawal.get("items")) {
            if (area.equals(item.get("area").asText())) return item;
        }
        throw new AssertionError("No '" + area + "' line on the checklist");
    }

    // ---------------------------------------------------------------- helpers

    private UUID spareCopy(com.schoolsoft.certification.support.CertificationFixture.SchoolSeed school) {
        return queryOne(
            "SELECT c.id FROM library_copy c JOIN library_title t ON t.id = c.title_id " +
            "WHERE t.school_id = ? AND c.status = 'available' ORDER BY c.barcode DESC LIMIT 1",
            UUID.class, school.id());
    }

    private double duesOf(UUID studentId) {
        Double dues = queryOne(
            "SELECT COALESCE(sum(total - paid), 0) FROM fee_invoice WHERE student_id = ? " +
            "  AND status IN ('open','partial','overdue')", Double.class, studentId);
        return dues == null ? 0 : dues;
    }

    private void clearLibraryCharges(UUID issueId, UUID studentId) {
        inChainDo(jdbc -> {
            jdbc.update("UPDATE library_issue SET fee_adjustment_id = NULL WHERE id = ?", issueId);
            jdbc.update("DELETE FROM ledger_entry WHERE source_id IN " +
                "(SELECT a.id FROM fee_adjustment a JOIN fee_invoice i ON i.id = a.fee_invoice_id " +
                " WHERE i.student_id = ? AND a.kind = 'charge')", studentId);
            jdbc.update("DELETE FROM fee_adjustment WHERE fee_invoice_id IN " +
                "(SELECT id FROM fee_invoice WHERE student_id = ? AND cycle_label LIKE 'Miscellaneous%')",
                studentId);
            jdbc.update("DELETE FROM fee_invoice_line WHERE fee_invoice_id IN " +
                "(SELECT id FROM fee_invoice WHERE student_id = ? AND cycle_label LIKE 'Miscellaneous%')",
                studentId);
            jdbc.update("DELETE FROM fee_invoice WHERE student_id = ? AND cycle_label LIKE 'Miscellaneous%'",
                studentId);
            jdbc.update("DELETE FROM library_issue WHERE id = ?", issueId);
            jdbc.update("DELETE FROM library_charge_policy WHERE school_id = ?", cbse().id());
        });
    }
}
