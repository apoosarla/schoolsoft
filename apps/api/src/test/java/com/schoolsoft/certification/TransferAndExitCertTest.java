package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolsoft.certification.support.AbstractCertificationTest;
import com.schoolsoft.certification.support.CertificationFixture.SchoolSeed;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-XFER — transfer out, withdrawal & TC. */
class TransferAndExitCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_XFER_01_withdrawalRunsAClearanceChecklist() {
        String token = principalToken(cbse());
        Leaver leaver = leaver("XFER01");

        // Something outstanding in two different areas, so the checklist has
        // work to do rather than passing by default.
        chargeFees(leaver.studentId(), 12000, "XFER01 term fee");
        UUID issueId = issueACopy(leaver.studentId(), "XFER01");
        rideTheBus(leaver.studentId(), "2026-04-01");

        var filed = post("/v1/enrolment/withdrawals", body(
            "enrolmentId", leaver.enrolmentId(), "reasonCode", "relocation",
            "reason", "Family moving to Pune", "lastWorkingDate", "2026-08-31"), token);
        assertThat(filed.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID withdrawalId = UUID.fromString(filed.getBody().get("id").asText());

        // The reason and the last working date are recorded, not implied.
        assertThat(filed.getBody().get("reasonCode").asText()).isEqualTo("relocation");
        assertThat(filed.getBody().get("reason").asText()).isEqualTo("Family moving to Pune");
        assertThat(filed.getBody().get("lastWorkingDate").asText()).isEqualTo("2026-08-31");
        assertThat(filed.getBody().get("state").asText()).isEqualTo("clearance_pending");

        // Four areas, each with its own answer.
        Map<String, JsonNode> items = itemsByArea(filed.getBody());
        assertThat(items.keySet()).containsExactlyInAnyOrder("fees", "library", "transport", "assets");
        assertThat(items.get("fees").get("state").asText()).isEqualTo("blocked");
        assertThat(items.get("fees").get("amount").asDouble()).isEqualTo(12000.0);
        assertThat(items.get("library").get("state").asText()).isEqualTo("blocked");
        assertThat(items.get("library").get("detail").asText()).contains("1 copy not returned");
        // Transport never blocks: there is nothing for the family to settle,
        // only a seat for the school to release when the child actually goes.
        assertThat(items.get("transport").get("state").asText()).isEqualTo("cleared");
        // Nothing in this system tracks an ID card or a locker key, so the line
        // waits for a person rather than reporting a clear it cannot know.
        assertThat(items.get("assets").get("state").asText()).isEqualTo("pending");

        // While anything is blocking, the exit does not happen.
        var refused = post("/v1/enrolment/withdrawals/" + withdrawalId + "/complete",
            body("reason", "Leaving today"), token);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(queryOne("SELECT status FROM enrolment WHERE id = ?", String.class, leaver.enrolmentId()))
            .isEqualTo("active");

        // Settle each area for real, then re-probe: the checklist reflects the
        // world rather than what somebody ticked. The copy comes back first,
        // because returning it late posts a fine — which lands on the fees line
        // and would leave the family owing money they had just paid off.
        post("/v1/library/issues/" + issueId + "/return", null, librarianToken(cbse()));
        var withFine = post("/v1/enrolment/withdrawals/" + withdrawalId + "/clearance/refresh", null, token);
        assertThat(itemsByArea(withFine.getBody()).get("fees").get("amount").asDouble())
            .isGreaterThan(12000.0);

        payInFull(leaver.studentId());
        var refreshed = post("/v1/enrolment/withdrawals/" + withdrawalId + "/clearance/refresh", null, token);
        Map<String, JsonNode> after = itemsByArea(refreshed.getBody());
        assertThat(after.get("fees").get("state").asText()).isEqualTo("cleared");
        assertThat(after.get("library").get("state").asText()).isEqualTo("cleared");

        // The assets line still needs a human, and says so.
        assertThat(refreshed.getBody().get("state").asText()).isEqualTo("clearance_pending");
        post("/v1/enrolment/withdrawals/" + withdrawalId + "/clearance/assets", body("reason", "ID card and library card returned at the counter"), token);

        var completed = post("/v1/enrolment/withdrawals/" + withdrawalId + "/complete",
            body("reason", "Clearance complete"), token);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody().get("state").asText()).isEqualTo("completed");
    }

    @Test @Tag("P1")
    void cert_XFER_02_transferCertificateCarriesStatutoryFieldsAndIsNumbered() {
        String token = principalToken(cbse());
        Leaver leaver = withdrawnLeaver("XFER02", "2026-08-31", "transfer_out",
            "Father transferred to Bengaluru");

        var issued = post("/v1/certificates", body(
            "studentId", leaver.studentId(), "kind", "transfer",
            "conduct", "Excellent", "remarks", "A conscientious pupil",
            "extras", Map.of("nationality", "Indian", "boardRegistrationNo", "CBSE-2026-88211")), token);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode payload = issued.getBody().get("payload");
        assertThat(payload.get("studentName").asText()).contains("XFER02");
        assertThat(payload.get("admissionNo").asText()).isNotBlank();
        assertThat(payload.get("dateOfBirth").asText()).isEqualTo("2015-05-05");
        assertThat(payload.get("dateOfAdmission").asText()).isEqualTo("2026-04-01");
        assertThat(payload.get("dateOfLeaving").asText()).isEqualTo("2026-08-31");
        assertThat(payload.get("lastClassStudied").asText()).isEqualTo("5-A");
        assertThat(payload.get("reasonForLeaving").asText()).isEqualTo("Father transferred to Bengaluru");
        assertThat(payload.get("conduct").asText()).isEqualTo("Excellent");
        assertThat(payload.get("workingDays").isNumber()).isTrue();
        assertThat(payload.get("attendancePct").isNumber()).isTrue();
        // Board registration is not a column here, so it comes from the
        // registrar rather than being invented — and it lands on the document.
        assertThat(payload.get("boardRegistrationNo").asText()).isEqualTo("CBSE-2026-88211");

        // Numbered by the school's series, not by the caller.
        String serial = issued.getBody().get("serialNo").asText();
        assertThat(serial).matches("TC/\\d{4}/\\d{4}");
        assertThat(payload.get("serialNo").asText()).isEqualTo(serial);

        // Non-repudiable: the stored document still hashes to what was signed.
        UUID certId = UUID.fromString(issued.getBody().get("id").asText());
        var verified = get("/v1/certificates/" + certId + "/verify", token);
        assertThat(verified.getBody().get("intact").asBoolean()).isTrue();
        assertThat(verified.getBody().get("revoked").asBoolean()).isFalse();

        // And it cannot be edited into saying something else — a correction is a
        // revocation and a reissue, which is what leaves the first serial
        // resolving to what the family was actually handed.
        assertThatTamperingIsRefused(certId);

        // The family can fetch their own child's certificate; another family cannot.
        String ownGuardian = guardianTokenFor(cbse(), leaver.studentId());
        var mine = get("/v1/certificates/students/" + leaver.studentId(), ownGuardian);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody()).hasSize(1);

        UUID otherChild = firstStudentIn(currentFocusSection(cbse()));
        var theirs = get("/v1/certificates/students/" + leaver.studentId(),
            guardianTokenFor(cbse(), otherChild));
        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test @Tag("P1")
    void cert_XFER_03_withdrawalDeListsTheStudentEverywhereButKeepsHistory() {
        String token = principalToken(cbse());
        Leaver leaver = leaver("XFER03");
        UUID section = currentFocusSection(cbse());
        rideTheBus(leaver.studentId(), "2026-04-01");

        // A day of attendance while they are still here, so there is history to
        // keep rather than an empty record that trivially survives.
        post("/v1/attendance/mark", body("schoolId", cbse().id(), "studentId", leaver.studentId(),
            "sectionId", section, "onDate", "2026-07-07", "status", "present"), token);

        // The withdrawal is filed today for a last working day a month out. Until
        // that day the child is still at school, and every roster must still say so.
        LocalDate lastDay = LocalDate.now().plusDays(30);
        UUID withdrawalId = fileAndClear(leaver, lastDay.toString(), "relocation", "Moving house");

        // Filed but not completed: nothing has changed yet, which is the point of
        // a clearance step that can still be cancelled.
        assertThat(rosterHas(section, leaver.studentId(), lastDay.plusDays(1).toString())).isTrue();

        post("/v1/enrolment/withdrawals/" + withdrawalId + "/complete",
            body("reason", "Clearance complete"), token);

        // Now the child is on the register up to and including their last day...
        assertThat(rosterHas(section, leaver.studentId(), null)).isTrue();
        assertThat(rosterHas(section, leaver.studentId(), lastDay.toString())).isTrue();
        // ...and off it the morning after, with no job having run.
        assertThat(rosterHas(section, leaver.studentId(), lastDay.plusDays(1).toString())).isFalse();

        // The enrolment closes on the last working day, naming why.
        assertThat(queryOne("SELECT status FROM enrolment WHERE id = ?", String.class, leaver.enrolmentId()))
            .isEqualTo("withdrawn");
        assertThat(queryOne("SELECT ends_on::text FROM enrolment WHERE id = ?",
            String.class, leaver.enrolmentId())).isEqualTo(lastDay.toString());

        // Transport releases the seat on the same date, without the transport
        // office being asked to remember (TRN-09 is the same mechanism).
        assertThat(queryOne("SELECT ends_on::text FROM student_transport WHERE student_id = ?",
            String.class, leaver.studentId())).isEqualTo(lastDay.toString());

        // History stays queryable: the class list for the day they were marked
        // present still contains them, and so does the mark itself.
        assertThat(rosterHas(section, leaver.studentId(), "2026-07-07")).isTrue();
        var marks = get("/v1/attendance/students/" + leaver.studentId()
            + "?from=2026-07-01&to=2026-07-31", token).getBody();
        assertThat(marks).hasSize(1);

        // The student record itself is not deleted — a leaver still has a past.
        assertThat(count("SELECT count(*) FROM student WHERE id = ?", leaver.studentId())).isEqualTo(1);
    }

    @Test @Tag("P1")
    @Disabled("GAP-04 — the withdrawal now closes the enrolment on a date, but there is still no alumni "
        + "identity: no post-exit access window on a login and nothing that revokes one when it "
        + "lapses, so 'read-only for a period, then revoked' has nowhere to live (GAP-04).")
    void cert_XFER_04_postWithdrawalParentAccessIsReadOnlyThenRevoked() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-15 — /enrolments/{id}/transfer moves a section only; there is no intra-chain school "
        + "transfer that preserves history and settles the source ledger (Phase 7).")
    void cert_XFER_05_intraChainSchoolTransferPreservesHistoryAndSettlesTheLedger() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-16 — no document store for prior-school records, so external marks cannot be held as "
        + "historical context (Phase 8).")
    void cert_XFER_06_externalTransferInCapturesPriorSchoolMarksAsContext() {
    }

    @Test @Tag("P2")
    void cert_XFER_07_duplicateOrInvalidTcRequestIsPrevented() {
        String token = principalToken(cbse());

        // A TC before the exit is complete is refused: the clearance checklist
        // is what gates the document, and issuing around it would make it a note.
        Leaver early = leaver("XFER07A");
        chargeFees(early.studentId(), 5000, "XFER07 outstanding");
        post("/v1/enrolment/withdrawals", body(
            "enrolmentId", early.enrolmentId(), "reasonCode", "other",
            "reason", "Undecided", "lastWorkingDate", "2026-09-30"), token);
        var premature = post("/v1/certificates", body(
            "studentId", early.studentId(), "kind", "transfer"), token);
        assertThat(premature.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // And for a child who never left at all.
        Leaver staying = leaver("XFER07B");
        var never = post("/v1/certificates", body("studentId", staying.studentId(), "kind", "transfer"), token);
        assertThat(never.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // A second TC against the same exit is refused.
        Leaver gone = withdrawnLeaver("XFER07C", "2026-08-31", "transfer_out", "Moving city");
        var first = post("/v1/certificates", body("studentId", gone.studentId(), "kind", "transfer"), token);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID firstId = UUID.fromString(first.getBody().get("id").asText());

        var duplicate = post("/v1/certificates", body("studentId", gone.studentId(), "kind", "transfer"), token);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // The way to correct one is to revoke it and reissue — which leaves the
        // serial the family holds resolving, and links the two.
        var reissued = post("/v1/certificates/" + firstId + "/revoke", body(
            "reason", "Date of birth was wrong on the first",
            "reissue", Map.of("studentId", gone.studentId(), "kind", "transfer",
                "conduct", "Good")), token);
        assertThat(reissued.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID replacementId = UUID.fromString(reissued.getBody().get("id").asText());
        assertThat(replacementId).isNotEqualTo(firstId);
        assertThat(reissued.getBody().get("serialNo").asText())
            .isNotEqualTo(first.getBody().get("serialNo").asText());

        var revoked = get("/v1/certificates/" + firstId, token);
        assertThat(revoked.getBody().get("revokedAt").isNull()).isFalse();
        assertThat(revoked.getBody().get("supersededById").asText()).isEqualTo(replacementId.toString());
        // Revoked, but still readable and still verifiable — that is the point
        // of revoking rather than deleting.
        assertThat(get("/v1/certificates/" + firstId + "/verify", token).getBody()
            .get("intact").asBoolean()).isTrue();
    }

    @Test @Tag("P1")
    void cert_XFER_08_withdrawalWithDuesIsBlockedOrOverriddenWithReason() {
        String registrar = registrarToken(cbse());
        Leaver leaver = leaver("XFER08");
        chargeFees(leaver.studentId(), 18400, "XFER08 term 2 fee");

        var filed = post("/v1/enrolment/withdrawals", body(
            "enrolmentId", leaver.enrolmentId(), "reasonCode", "financial",
            "reason", "Unable to continue", "lastWorkingDate", "2026-08-31"), registrar);
        UUID withdrawalId = UUID.fromString(filed.getBody().get("id").asText());
        assertThat(itemsByArea(filed.getBody()).get("fees").get("amount").asDouble()).isEqualTo(18400.0);

        clearNonFeeLines(withdrawalId, registrar);

        // Blocked while the money is outstanding.
        assertThat(post("/v1/enrolment/withdrawals/" + withdrawalId + "/complete",
            body("reason", "Leaving"), registrar).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // The registrar processes the exit but may not forgive its arrears: that
        // is a decision about money, and it belongs to whoever holds the money.
        var refusedWaiver = post("/v1/enrolment/withdrawals/" + withdrawalId + "/clearance/fees/waive",
            body("reason", "Hardship case"), registrar);
        assertThat(refusedWaiver.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // A waiver without a reason is refused even from somebody who may waive.
        // The audit interceptor runs ahead of method security, so a missing
        // reason answers 400 about the payload — which is what a caller sees.
        String accountant = accountantToken(cbse());
        assertThat(post("/v1/enrolment/withdrawals/" + withdrawalId + "/clearance/fees/waive",
            body("reason", ""), accountant).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        var waived = post("/v1/enrolment/withdrawals/" + withdrawalId + "/clearance/fees/waive", body(
            "reason", "Hardship: balance written off by the trust, minuted 2026-08-20"), accountant);
        assertThat(waived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(waived.getBody().get("state").asText()).isEqualTo("cleared");

        // The override is attributed and reasoned on the withdrawal itself, not
        // buried in a checklist row nobody reads back.
        assertThat(waived.getBody().get("duesOverrideReason").asText()).contains("minuted 2026-08-20");
        assertThat(queryOne("SELECT dues_override_by_staff_id FROM withdrawal WHERE id = ?",
            UUID.class, withdrawalId)).isEqualTo(cbse().accountantStaffId());

        var completed = post("/v1/enrolment/withdrawals/" + withdrawalId + "/complete",
            body("reason", "Cleared with an authorised waiver"), registrar);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The debt itself is not erased by the exit. A waiver releases the child,
        // not the invoice — writing it off is a fee adjustment and stays one.
        assertThat(count("SELECT count(*) FROM fee_invoice WHERE student_id = ? " +
            "  AND status IN ('open','partial','overdue')", leaver.studentId())).isEqualTo(1);

        // Completing again is the same answer, not a conflict: a retry after a
        // dropped response must not fail because the first attempt worked.
        var retried = post("/v1/enrolment/withdrawals/" + withdrawalId + "/complete",
            body("reason", "Retry"), registrar);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().get("state").asText()).isEqualTo("completed");
    }

    // ------------------------------------------------------------------ setup

    record Leaver(UUID studentId, UUID enrolmentId) {}

    /** A student of this scenario's own, enrolled in the focus section. */
    private Leaver leaver(String tag) {
        String token = principalToken(cbse());
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        // An admission number of the scenario's own: the fixture smoke test counts
        // the seeded cohort by its `ADM%` numbers, and a scenario borrowing that
        // prefix makes the harness's own guard fail on a run's residue.
        var created = post("/v1/people/students", body(
            "schoolId", cbse().id(), "admissionNo", tag + "-" + suffix,
            "firstName", tag, "lastName", "Leaver-" + suffix,
            "dob", "2015-05-05", "gender", "male"), token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID studentId = UUID.fromString(created.getBody().get("id").asText());

        var enrolled = post("/v1/enrolment", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", currentFocusSection(cbse()),
            "academicYearId", cbse().currentAy().id(), "startsOn", "2026-04-01",
            "overCapacityReason", "Certification scenario " + tag), token);
        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID enrolmentId = UUID.fromString(enrolled.getBody().get("id").asText());

        attachGuardian(studentId, tag, suffix);
        return new Leaver(studentId, enrolmentId);
    }

    /** A leaver whose withdrawal has already been filed, cleared and completed. */
    private Leaver withdrawnLeaver(String tag, String lastWorkingDate, String reasonCode, String reason) {
        Leaver leaver = leaver(tag);
        UUID withdrawalId = fileAndClear(leaver, lastWorkingDate, reasonCode, reason);
        var completed = post("/v1/enrolment/withdrawals/" + withdrawalId + "/complete",
            body("reason", "Clearance complete"), principalToken(cbse()));
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return leaver;
    }

    private UUID fileAndClear(Leaver leaver, String lastWorkingDate, String reasonCode, String reason) {
        String token = principalToken(cbse());
        var filed = post("/v1/enrolment/withdrawals", body(
            "enrolmentId", leaver.enrolmentId(), "reasonCode", reasonCode,
            "reason", reason, "lastWorkingDate", lastWorkingDate), token);
        assertThat(filed.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID withdrawalId = UUID.fromString(filed.getBody().get("id").asText());
        clearNonFeeLines(withdrawalId, token);
        post("/v1/enrolment/withdrawals/" + withdrawalId + "/clearance/fees",
            body("reason", "Nothing outstanding"), token);
        return withdrawalId;
    }

    private void clearNonFeeLines(UUID withdrawalId, String token) {
        for (String area : List.of("library", "transport", "assets")) {
            post("/v1/enrolment/withdrawals/" + withdrawalId + "/clearance/" + area,
                body("reason", "Checked at the counter"), token);
        }
    }

    private void attachGuardian(UUID studentId, String tag, String suffix) {
        inChainDo(jdbc -> {
            UUID guardianId = UUID.randomUUID();
            jdbc.update("INSERT INTO guardian (id, school_id, first_name, last_name, phone, email) " +
                "VALUES (?, ?, ?, 'Parent', ?, ?)",
                guardianId, cbse().id(), tag, "+9199" + suffix.hashCode(),
                tag.toLowerCase() + "-" + suffix + "@cert.test");
            jdbc.update("INSERT INTO guardian_student (guardian_id, student_id, relation, is_primary) " +
                "VALUES (?, ?, 'father', TRUE)", guardianId, studentId);
            jdbc.update("INSERT INTO user_account (school_id, subject_type, subject_id, email) " +
                "VALUES (?, 'guardian', ?, ?)",
                cbse().id(), guardianId, tag.toLowerCase() + "-" + suffix + "@cert.test");
        });
    }

    private void chargeFees(UUID studentId, double amount, String label) {
        UUID feeHead = queryOne("SELECT id FROM fee_head WHERE school_id = ? ORDER BY code LIMIT 1",
            UUID.class, cbse().id());
        var invoice = post("/v1/fees/invoices", body(
            "schoolId", cbse().id(), "studentId", studentId,
            "invoiceNo", "CERT-" + UUID.randomUUID().toString().substring(0, 8),
            "cycleLabel", label, "dueOn", "2026-08-10",
            "lines", List.of(Map.of("feeHeadId", feeHead, "description", label, "amount", amount))),
            accountantToken(cbse()));
        assertThat(invoice.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void payInFull(UUID studentId) {
        String token = accountantToken(cbse());
        List<UUID> open = queryList("SELECT id FROM fee_invoice WHERE student_id = ? " +
            "  AND status IN ('open','partial','overdue')", UUID.class, studentId);
        for (UUID invoiceId : open) {
            double due = queryOne("SELECT (total - paid)::float8 FROM fee_invoice WHERE id = ?",
                Double.class, invoiceId);
            post("/v1/fees/payments", body(
                "schoolId", cbse().id(), "feeInvoiceId", invoiceId, "amount", due,
                "gateway", "cash", "method", "cash",
                "idempotencyKey", "cert-" + invoiceId), token);
        }
    }

    private UUID issueACopy(UUID studentId, String tag) {
        String token = librarianToken(cbse());
        var title = post("/v1/library/titles?schoolId=" + cbse().id(), body(
            "title", tag + " Reader", "author", "Certification", "isbn", "ISBN-" + tag), token);
        UUID titleId = UUID.fromString(title.getBody().get("id").asText());
        var copy = post("/v1/library/titles/" + titleId + "/copies",
            body("barcode", "BC-" + tag + "-" + UUID.randomUUID().toString().substring(0, 6)), token);
        UUID copyId = UUID.fromString(copy.getBody().get("id").asText());
        var issued = post("/v1/library/issues", body(
            "schoolId", cbse().id(), "copyId", copyId, "memberType", "student",
            "memberId", studentId, "dueOn", "2026-08-20"), token);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(issued.getBody().get("id").asText());
    }

    private void rideTheBus(UUID studentId, String from) {
        SchoolSeed school = cbse();
        UUID stopId = queryOne("SELECT id FROM transport_stop WHERE route_id = ? ORDER BY sort_order LIMIT 1",
            UUID.class, school.routeId());
        var assigned = post("/v1/transport/student-assignments", body(
            "schoolId", school.id(), "studentId", studentId, "routeId", school.routeId(),
            "stopId", stopId, "startsOn", from), principalToken(school));
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------- assertions

    private Map<String, JsonNode> itemsByArea(JsonNode withdrawal) {
        var out = new java.util.LinkedHashMap<String, JsonNode>();
        withdrawal.get("items").forEach(item -> out.put(item.get("area").asText(), item));
        return out;
    }

    private boolean rosterHas(UUID sectionId, UUID studentId, String onDate) {
        var roster = get("/v1/enrolment/sections/" + sectionId
            + (onDate == null ? "" : "?onDate=" + onDate), principalToken(cbse())).getBody();
        for (JsonNode row : roster) {
            if (studentId.toString().equals(row.get("studentId").asText())) return true;
        }
        return false;
    }

    /**
     * The append-only trigger: a certificate's own row refuses to become a
     * different document, so the serial a family holds cannot be quietly
     * repointed at different content.
     */
    private void assertThatTamperingIsRefused(UUID certId) {
        try {
            inChainDo(jdbc -> jdbc.update(
                "UPDATE certificate SET payload = '{\"studentName\":\"Someone Else\"}'::json WHERE id = ?",
                certId));
            throw new AssertionError("A certificate's payload was editable in place");
        } catch (org.springframework.dao.DataAccessException expected) {
            assertThat(expected.getMostSpecificCause().getMessage()).contains("issued once");
        }
    }
}
