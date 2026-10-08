package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolsoft.certification.support.AbstractCertificationTest;
import com.schoolsoft.iam.internal.OtpStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** CERT-SEC — roles, access & security. */
class SecurityCertTest extends AbstractCertificationTest {

    @Autowired private OtpStore otps;

    private String guardianPhone(int nth) {
        return queryOne("SELECT phone FROM user_account WHERE subject_type = 'guardian' AND is_active "
            + "AND school_id = ? ORDER BY phone LIMIT 1 OFFSET " + nth, String.class, cbse().id());
    }

    private List<String> screensOf(String token) {
        List<String> keys = new ArrayList<>();
        get("/v1/iam/me/screens", token).getBody().get("screenKeys").forEach(k -> keys.add(k.asText()));
        return keys;
    }

    private ResponseEntity<JsonNode> verifyOtp(String phone, String code) {
        return post("/v1/auth/otp/verify",
            Map.of("identifier", phone, "chainSlug", seed.chainSlug(), "code", code), null);
    }

    @Test @Tag("P1")
    void cert_SEC_01_otpLoginRejectsExpiredReusedAndBruteForcedCodes() {
        String phone = guardianPhone(0);

        // The code that was issued signs the guardian in, as a guardian.
        String code = otps.issue(phone, seed.chainSlug());
        var signedIn = verifyOtp(phone, code);
        assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signedIn.getBody().get("profile").get("subjectType").asText()).isEqualTo("guardian");

        // Once. A copy of a used code is worth nothing.
        assertThat(verifyOtp(phone, code).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // And only for five minutes.
        String stale = otps.issue(phone, seed.chainSlug());
        try {
            clock.pin(Instant.now().plus(Duration.ofMinutes(6)));
            assertThat(verifyOtp(phone, stale).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        } finally {
            clock.release();
        }

        // Guessing locks the account: after five wrong codes the right one is
        // refused too, with an answer that does not say whether it was right.
        String target = guardianPhone(1);
        String real = otps.issue(target, seed.chainSlug());
        String wrong = real.equals("135790") ? "246801" : "135790";
        for (int i = 0; i < 5; i++) {
            assertThat(verifyOtp(target, wrong).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(verifyOtp(target, wrong).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(verifyOtp(target, otps.issue(target, seed.chainSlug())).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Another family is not locked out by it.
        String bystander = guardianPhone(2);
        assertThat(verifyOtp(bystander, otps.issue(bystander, seed.chainSlug())).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }

    @Test @Tag("P1")
    void cert_SEC_02_expiredAccessTokenIsRejectedAndRefreshIssuesANewOne() {
        UUID userId = cbse().principalUserId();

        // An access token whose lifetime has passed is refused, not silently accepted.
        String expired = expiredAccessToken(userId);
        var withExpired = get("/v1/tenancy/schools", expired);
        assertThat(withExpired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The refresh token exchanges for a working access token without re-authenticating.
        String refresh = jwt.issueRefresh(userId, seed.chainId().toString(), seed.chainSchema());
        var refreshed = post("/v1/auth/refresh", Map.of("refreshToken", refresh), null);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newAccess = refreshed.getBody().get("accessToken").asText();
        assertThat(get("/v1/tenancy/schools", newAccess).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The refresh rotates: a new refresh token comes back, and once it is
        // signed out it is spent — a copy of it exchanges for nothing.
        String next = refreshed.getBody().get("refreshToken").asText();
        assertThat(next).isNotEqualTo(refresh);
        assertThat(post("/v1/auth/logout", Map.of("refreshToken", next), null).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post("/v1/auth/refresh", Map.of("refreshToken", next), null).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);

        // An access token presented to the refresh endpoint is refused.
        var wrongType = post("/v1/auth/refresh", Map.of("refreshToken", principalToken(cbse())), null);
        assertThat(wrongType.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test @Tag("P1")
    void cert_SEC_03_screenAccessIsEnforcedServerSide() {
        // /me/screens says what the menu shows. What refuses a hand-crafted call is
        // the permission behind the endpoint, so each role here is sent to a module
        // its menu does not carry and has to be turned away by the server.
        String librarian = librarianToken(cbse());
        assertThat(screensOf(librarian)).contains("library").doesNotContain("fees", "admin");
        assertThat(get("/v1/library/titles?schoolId=" + cbse().id(), librarian).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/fees/reports/day-book?schoolId=" + cbse().id()
            + "&from=2026-08-01&to=2026-08-31", librarian).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/iam/roles", librarian).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String accountant = accountantToken(cbse());
        assertThat(screensOf(accountant)).contains("fees").doesNotContain("library", "admin");
        assertThat(get("/v1/fees/reports/day-book?schoolId=" + cbse().id()
            + "&from=2026-08-01&to=2026-08-31", accountant).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/iam/roles", accountant).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // A write is refused the same way as a read.
        String teacher = teacherToken(cbse(), 1);
        assertThat(screensOf(teacher)).doesNotContain("fees", "admin");
        assertThat(post("/v1/fees/generate", body(
            "schoolId", cbse().id(),
            "academicYearId", cbse().currentAy().id(),
            "cycleLabel", "should-never-run"), teacher).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test @Tag("P1")
    void cert_SEC_04_rowLevelSecurityBlocksCrossSchoolReadsIncludingIdEnumeration() {
        String cbseToken = principalToken(cbse());
        UUID cieSection = currentFocusSection(cie());
        UUID cieStudent = firstStudentIn(cieSection);
        UUID cieInvoice = queryOne("SELECT id FROM fee_invoice WHERE student_id = ? LIMIT 1", UUID.class, cieStudent);

        // Direct id enumeration across the school boundary.
        assertThat(get("/v1/people/students/" + cieStudent, cbseToken).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/fees/invoices/" + cieInvoice, cbseToken).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/enrolment/sections/" + cieSection, cbseToken).getBody()).isEmpty();
        assertThat(get("/v1/assessment?sectionId=" + cieSection, cbseToken).getBody()).isEmpty();
        assertThat(get("/v1/comms/announcements?schoolId=" + cie().id(), cbseToken).getBody()).isEmpty();

        // The route-keyed transport reads are the same shape: the path names a
        // route id and no school, so nothing above the database narrows them.
        // RLS on the school_id-carrying tables is what makes the answer empty.
        assertThat(get("/v1/transport/routes/" + cie().routeId() + "/students", cbseToken).getBody()).isEmpty();
        assertThat(get("/v1/transport/routes/" + cie().routeId() + "/stops", cbseToken).getBody()).isEmpty();
        assertThat(get("/v1/transport/vehicles?schoolId=" + cie().id(), cbseToken).getBody()).isEmpty();
        assertThat(get("/v1/transport/trips?schoolId=" + cie().id(), cbseToken).getBody()).isEmpty();

        // A bus's GPS trail is the one transport read the database cannot
        // narrow on its own: `gps_ping` carries no school_id, so V009 gave it
        // no policy, and every guardian holds `transport.track`. The read
        // joins `vehicle` to borrow the policy that exists.
        UUID cieVehicle = queryOne("SELECT id FROM vehicle WHERE school_id = ? LIMIT 1", UUID.class, cie().id());
        inChainDo(jdbc -> jdbc.update(
            "INSERT INTO gps_ping (vehicle_id, occurred_at, lat, lng) VALUES (?, now(), 17.44, 78.44)",
            cieVehicle));
        assertThat(get("/v1/transport/vehicles/" + cieVehicle + "/gps-pings", cbseToken).getBody()).isEmpty();
        assertThat(get("/v1/transport/vehicles/" + cieVehicle + "/gps-pings", principalToken(cie())).getBody())
            .isNotEmpty();

        // And the same reads succeed for the school that owns them.
        assertThat(get("/v1/people/students/" + cieStudent, principalToken(cie())).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }

    /**
     * The chain is what a chain admin reads, and the chain is all they reach.
     *
     * <p>This scenario used to read a section in each school to show the chain
     * being read across. That worked for a reason worth stating: a chain
     * admin's token carries no school, V009's policy stands aside for a
     * session with no school, and so <em>any</em> school-scoped read answered
     * with every school's rows. It was the same mechanism as the schools list,
     * pointed at a child's section.</p>
     *
     * <p>That was tolerable while such a session only existed in a console
     * that asked for nothing else. It is not tolerable now that a chain admin
     * signs in to the same app as the office, so
     * {@code TenantResolverFilter.CHAIN_ADMIN_PREFIXES} confines them and this
     * scenario asserts the confinement instead of the reach. The half that
     * matters most is unchanged: another chain's token sees nothing here, and
     * that isolation is the schema rather than any filter.</p>
     */
    @Test @Tag("P1")
    void cert_SEC_05_chainAdminReadsAcrossTheChainButNotAnotherChain() {
        var schools = get("/v1/tenancy/schools", chainAdminToken()).getBody();
        assertThat(schools).hasSize(2);

        // Reaching into one of those schools is refused before the handler
        // runs — by the filter, not by a permission, because the permission
        // is one a chain admin genuinely holds.
        var reachedIn = get("/v1/enrolment/sections/" + currentFocusSection(cbse()), chainAdminToken());
        assertThat(reachedIn.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/people/students?schoolId=" + cie().id(), chainAdminToken()).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        // A token minted for a different chain resolves against that chain's schema, which has no
        // access to this chain's rows — the isolation is the schema, not a filter.
        provisionOtherChain();
        UUID otherChainId = platformJdbc.queryForObject(
            "SELECT id FROM platform.chain WHERE slug = ?", UUID.class, OTHER_CHAIN);
        String otherChainToken = jwt.issueAccess(UUID.randomUUID(), otherChainId.toString(),
            "chain_" + OTHER_CHAIN, null, "chain_admin");
        assertThat(get("/v1/tenancy/schools", otherChainToken).getBody()).isEmpty();
    }

    /**
     * The operator's door is its own, and everything that comes through it is
     * written down.
     *
     * <p>Separately authenticated: the account is a {@code platform_user}, not
     * a {@code user_account} in anybody's chain, and neither door opens for
     * the other's people. Fully audited: a row per request in
     * {@code platform.operator_audit_log}, written by an interceptor that asks
     * who is calling rather than which endpoint was marked — so the reads into
     * a customer's chain are there beside the writes, and so is the request
     * that was refused.</p>
     */
    @Test @Tag("P1")
    void cert_SEC_06_platformAdminActionsAreSeparatelyAuthenticatedAndAudited() {
        String operatorEmail = "admin@schoolsoft.dev";
        UUID operatorId = platformJdbc.queryForObject(
            "SELECT id FROM platform.platform_user WHERE email = ?", UUID.class, operatorEmail);
        long before = platformJdbc.queryForObject(
            "SELECT COALESCE(max(id), 0) FROM platform.operator_audit_log", Long.class);

        // A school's head holds every permission inside their school and
        // nothing above it; nor does the chain's own HQ.
        for (String chainToken : List.of(principalToken(cbse()), chainAdminToken())) {
            assertThat(get("/v1/platform-admin/chains", chainToken).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get("/v1/platform-admin/audit", chainToken).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        }

        // Neither door opens for the other's people, even holding a good code.
        String chainEmail = queryOne("SELECT email FROM user_account WHERE email IS NOT NULL AND is_active "
            + "ORDER BY email LIMIT 1", String.class);
        var chainPersonAtPlatformDoor = post("/v1/auth/platform-admin/otp/verify",
            Map.of("email", chainEmail, "code", otps.issueForPlatformAdmin(chainEmail)), null);
        assertThat(chainPersonAtPlatformDoor.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var operatorAtChainDoor = verifyOtp(operatorEmail, otps.issue(operatorEmail, seed.chainSlug()));
        assertThat(operatorAtChainDoor.getStatusCode().is2xxSuccessful()).isFalse();

        // Through the operator's own door, with a code issued for it.
        var signedIn = post("/v1/auth/platform-admin/otp/verify",
            Map.of("email", operatorEmail, "code", otps.issueForPlatformAdmin(operatorEmail)), null);
        assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        String operator = signedIn.getBody().get("accessToken").asText();

        // A read above every chain, a read into one, a write, a write that is
        // refused, and a read of a chain that is not there.
        String base = "/v1/platform-admin/chains";
        UUID nowhere = UUID.randomUUID();
        assertThat(get(base, operator).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(base + "/" + seed.chainId() + "/stats", operator).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        var provisioned = post(base, Map.of("slug", OTHER_CHAIN, "name", "Other Chain", "planCode", "starter"),
            operator);
        assertThat(provisioned.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID otherChainId = UUID.fromString(provisioned.getBody().get("chainId").asText());
        assertThat(post(base + "/" + seed.chainId() + "/admins",
            Map.of("email", "sec06.second.hq@oakridge.test"), operator).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(get(base + "/" + nowhere + "/stats", operator).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        // Each of them is a row: who, what, about which chain, and how it ended.
        var trail = platformJdbc.queryForList(
            "SELECT action, path, chain_id, status, request_payload::text AS payload "
                + "FROM platform.operator_audit_log WHERE id > ? AND actor_user_id = ? ORDER BY id",
            before, operatorId);
        assertThat(trail).extracting(r -> r.get("action") + " -> " + r.get("status")).containsExactly(
            "POST /v1/auth/platform-admin/otp/verify -> 200",
            "GET /v1/platform-admin/chains -> 200",
            "GET /v1/platform-admin/chains/{id}/stats -> 200",
            "POST /v1/platform-admin/chains -> 200",
            "POST /v1/platform-admin/chains/{id}/admins -> 409",
            "GET /v1/platform-admin/chains/{id}/stats -> 404");
        assertThat(trail).extracting(r -> r.get("chain_id")).containsExactly(
            null, null, seed.chainId(), otherChainId, seed.chainId(), nowhere);
        // The path as asked, so the row says which chain without a join.
        assertThat((String) trail.get(2).get("path")).endsWith("/chains/" + seed.chainId() + "/stats");
        // What was asked for is kept; the one-time code that opened the door is not.
        assertThat((String) trail.get(3).get("payload")).contains(OTHER_CHAIN);
        assertThat((String) trail.get(4).get("payload")).contains("sec06.second.hq@oakridge.test");
        assertThat(trail.get(0).get("payload")).isNull();

        // Nobody else's requests are in it: the refusals above had no operator to name.
        assertThat(platformJdbc.queryForObject(
            "SELECT count(*) FROM platform.operator_audit_log WHERE id > ?", Long.class, before))
            .isEqualTo(trail.size());

        // An operator can read the trail of one chain, named by who did it —
        // and reading it is itself an act.
        var read = get("/v1/platform-admin/audit?chainId=" + seed.chainId(), operator);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> readBack = new ArrayList<>();
        read.getBody().forEach(e -> {
            if (e.get("id").asLong() > before) readBack.add(e.get("action").asText());
            assertThat(e.get("chainId").asText()).isEqualTo(seed.chainId().toString());
        });
        assertThat(readBack).containsExactly(
            "POST /v1/platform-admin/chains/{id}/admins", "GET /v1/platform-admin/chains/{id}/stats");
        assertThat(read.getBody().get(0).get("actorEmail").asText()).isEqualTo(operatorEmail);
        assertThat(platformJdbc.queryForObject(
            "SELECT action FROM platform.operator_audit_log ORDER BY id DESC LIMIT 1", String.class))
            .isEqualTo("GET /v1/platform-admin/audit");

        // And it stays written: no row is edited, none is removed.
        long firstRow = before + 1;
        assertThatThrownBy(() -> platformJdbc.update(
            "UPDATE platform.operator_audit_log SET status = 200 WHERE id > ?", before))
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> platformJdbc.update(
            "DELETE FROM platform.operator_audit_log WHERE id = ?", firstRow))
            .hasMessageContaining("append-only");
    }

    @Test @Tag("P1")
    void cert_SEC_07_fileTicketsAreTenantScopedExpiringAndNonGuessable() {
        String token = principalToken(cbse());
        var ticket = post("/v1/files/upload-ticket",
            Map.of("filename", "report.pdf", "mimeType", "application/pdf", "sizeBytes", 2048), token);
        assertThat(ticket.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID fileId = UUID.fromString(ticket.getBody().get("fileId").asText());
        String objectKey = ticket.getBody().get("objectKey").asText();
        assertThat(objectKey).contains(cbse().id().toString()).contains(fileId.toString());
        assertThat(java.time.Instant.parse(ticket.getBody().get("expiresAt").asText()))
            .isAfter(java.time.Instant.now());

        assertThat(get("/v1/files/" + fileId + "/download-ticket", token).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/files/" + fileId + "/download-ticket", principalToken(cie())).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/v1/files/" + UUID.randomUUID() + "/download-ticket", token).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @Tag("P1")
    void cert_SEC_08_highRiskMutationsAreAuditLogged() {
        String principal = principalToken(cbse());
        String accountant = accountantToken(cbse());

        // 1. Enrolment status change — and the reason is not optional.
        UUID studentId = queryOne("SELECT student_id FROM enrolment WHERE section_id = ? ORDER BY roll_no "
            + "OFFSET 6 LIMIT 1", UUID.class, currentFocusSection(cbse()));
        UUID enrolmentId = queryOne("SELECT id FROM enrolment WHERE student_id = ? AND status = 'active'",
            UUID.class, studentId);

        var noReason = post("/v1/enrolment/" + enrolmentId + "/status",
            Map.of("status", "withdrawn"), principal);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noReason.getBody().get("code").asText()).isEqualTo("reason_required");
        assertThat(queryOne("SELECT status FROM enrolment WHERE id = ?", String.class, enrolmentId))
            .isEqualTo("active");

        var withdrawn = post("/v1/enrolment/" + enrolmentId + "/status",
            Map.of("status", "withdrawn", "reason", "Family relocating; TC requested"), principal);
        assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertAudited("enrolment.status_change", enrolmentId, cbse().principalUserId(),
            "Family relocating; TC requested");
        // The entry carries the row on both sides, not just the fact of a change.
        assertThat(queryOne("SELECT before_state->>'status' FROM audit_log "
            + "WHERE action = 'enrolment.status_change' AND target_id = ? ORDER BY occurred_at DESC LIMIT 1",
            String.class, enrolmentId)).isEqualTo("active");
        assertThat(queryOne("SELECT after_state->>'status' FROM audit_log "
            + "WHERE action = 'enrolment.status_change' AND target_id = ? ORDER BY occurred_at DESC LIMIT 1",
            String.class, enrolmentId)).isEqualTo("withdrawn");
        post("/v1/enrolment/" + enrolmentId + "/status",
            Map.of("status", "active", "reason", "Relocation cancelled; child stays"), principal);

        // 2. Mark unlock — reopening a published assessment.
        UUID assessmentId = UUID.fromString(post("/v1/assessment", Map.of(
            "schoolId", cbse().id(), "sectionId", currentFocusSection(cbse()),
            "subjectId", subjectOf(cbse(), cbse().subjectCodes().get(0)),
            "termId", termOf(cbse(), cbse().currentAy().code(), "T1"),
            "strategyCode", cbse().strategyCode(), "name", "SEC-08 unlock probe",
            "assessmentType", "PT", "maxMarks", 20.0), principal).getBody().get("id").asText());
        post("/v1/assessment/" + assessmentId + "/status", Map.of("status", "published"), principal);

        var unlockWithoutRole = post("/v1/assessment/" + assessmentId + "/status",
            Map.of("status", "marking", "reason", "Re-evaluation requested"), teacherToken(cbse(), 1));
        assertThat(unlockWithoutRole.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var unlockWithoutReason = post("/v1/assessment/" + assessmentId + "/status",
            Map.of("status", "marking"), principal);
        assertThat(unlockWithoutReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var unlocked = post("/v1/assessment/" + assessmentId + "/status",
            Map.of("status", "marking", "reason", "Board asked for a re-evaluation"), principal);
        assertThat(unlocked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertAudited("assessment.status_change", assessmentId, cbse().principalUserId(),
            "Board asked for a re-evaluation");

        // 3. Fee waiver.
        UUID invoiceId = queryOne("SELECT id FROM fee_invoice WHERE school_id = ? AND status <> 'cancelled' "
            + "ORDER BY created_at LIMIT 1", UUID.class, cbse().id());
        var waived = post("/v1/fees/invoices/" + invoiceId + "/adjustments", body(
            "schoolId", cbse().id(), "kind", "waiver", "amount", 100.0,
            "reason", "Hardship waiver approved by the trust",
            "approvedByStaffId", cbse().principalStaffId()), accountant);
        assertThat(waived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertAudited("fee.adjustment", invoiceId, cbse().accountantUserId(),
            "Hardship waiver approved by the trust");

        // 4. Role grant.
        UUID staffId = cbse().teacherStaffIds().get(4);
        var granted = post("/v1/iam/staff-roles/assign", body(
            "staffId", staffId, "schoolId", cbse().id(), "roleCode", "exams_officer",
            "reason", "Covering exams while the officer is on leave"), principal);
        assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertAudited("role.granted", staffId, cbse().principalUserId(),
            "Covering exams while the officer is on leave");

        var revoked = post("/v1/iam/staff-roles/unassign", body(
            "staffId", staffId, "schoolId", cbse().id(), "roleCode", "exams_officer",
            "reason", "Officer back from leave"), principal);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertAudited("role.revoked", staffId, cbse().principalUserId(), "Officer back from leave");

        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM assessment WHERE id = ?", assessmentId);
            jdbc.update("DELETE FROM staff_role WHERE staff_id = ? AND role_code = 'exams_officer'", staffId);
        });
    }

    /** One audit entry naming the actor, the reason and both sides of the change. */
    private void assertAudited(String action, UUID targetId, UUID actorUserId, String reason) {
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ? "
            + "AND actor_user_id = ? AND reason = ?", action, targetId, actorUserId, reason))
            .as("audit entry for %s", action)
            .isEqualTo(1);
    }

    /**
     * What the DPDP Act gives a family over their child's data, end to end.
     *
     * <p>Consent is given and taken back, and both dates are kept. A copy of
     * everything held is served the moment it is asked for. An erasure waits
     * for the office and carries a deadline fixed when it was filed; it is
     * refused while the child is on a register, and when it is served it
     * removes who the child and their family were while leaving the rows the
     * school must keep.</p>
     */
    @Test @Tag("P1")
    void cert_SEC_09_dpdpConsentExportAndErasureAreServable() {
        String registrar = registrarToken(cbse());
        List<UUID> cohort = queryList("SELECT student_id FROM enrolment WHERE section_id = ? "
            + "ORDER BY roll_no OFFSET 8 LIMIT 2", UUID.class, currentFocusSection(cbse()));
        UUID child = cohort.get(0);
        String parent = guardianTokenFor(cbse(), child);
        String otherParent = guardianTokenFor(cbse(), cohort.get(1));
        String consents = "/v1/privacy/students/" + child + "/consents";

        // --- consent: given, given again, withdrawn ---
        var given = post(consents, body("purpose", "photo_publish", "granted", true, "source", "parent_app"),
            parent);
        assertThat(given.getStatusCode()).isEqualTo(HttpStatus.OK);
        post(consents, body("purpose", "photo_publish", "granted", true, "source", "parent_app"), parent);
        assertThat(count("SELECT count(*) FROM consent_record WHERE subject_id = ? "
            + "AND purpose = 'photo_publish' AND revoked_at IS NULL", child))
            .as("granting twice is a retry, not a second consent").isEqualTo(1);
        assertThat(get(consents, parent).getBody().get(0).get("standing").asBoolean()).isTrue();

        var withdrawn = post(consents, body("purpose", "photo_publish", "granted", false), parent);
        assertThat(withdrawn.getBody()).hasSize(1);
        assertThat(withdrawn.getBody().get(0).get("standing").asBoolean()).isFalse();
        // Both dates survive: when they agreed, and when they stopped.
        assertThat(withdrawn.getBody().get(0).get("grantedAt").asText()).isNotBlank();
        assertThat(withdrawn.getBody().get(0).get("revokedAt").asText()).isNotBlank();

        // Somebody else's child is not theirs to answer for, or to read.
        assertThat(get(consents, otherParent).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(consents, body("purpose", "photo_publish", "granted", true), otherParent)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // --- access: asked and served in one step, with a copy of everything ---
        var access = post("/v1/privacy/requests", body("studentId", child, "kind", "access"), parent);
        assertThat(access.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(access.getBody().get("status").asText()).isEqualTo("fulfilled");
        String accessId = access.getBody().get("id").asText();

        var export = get("/v1/privacy/requests/" + accessId + "/export", parent);
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(export.getBody().get("student").get("id").asText()).isEqualTo(child.toString());
        assertThat(export.getBody().get("guardians")).isNotEmpty();
        assertThat(export.getBody().get("consents")).hasSize(1);
        // Found by what hangs off the child, not from a list somebody maintains.
        JsonNode records = export.getBody().get("records");
        assertThat(records.get("enrolment")).isNotEmpty();
        assertThat(records.get("attendance_record")).isNotEmpty();
        assertThat(records.get("data_request")).isNotEmpty();
        assertThat(export.getBody().get("student").has("encrypted_payload")).isFalse();
        assertThat(get("/v1/privacy/requests/" + accessId + "/export", otherParent).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        // --- erasure of a child still at the school: filed, on the clock, refused ---
        var asked = post("/v1/privacy/requests",
            body("studentId", child, "kind", "erasure", "note", "Please remove our details"), parent);
        assertThat(asked.getBody().get("status").asText()).isEqualTo("open");
        String erasureId = asked.getBody().get("id").asText();
        LocalDate filedOn = LocalDate.parse(asked.getBody().get("requestedOn").asText());
        assertThat(LocalDate.parse(asked.getBody().get("dueOn").asText())).isEqualTo(filedOn.plusDays(30));
        // Asking again while it is open is the same request.
        assertThat(post("/v1/privacy/requests", body("studentId", child, "kind", "erasure"), parent)
            .getBody().get("id").asText()).isEqualTo(erasureId);

        try {
            // The office's queue; a teacher has no business in it, and a
            // parent sees their own and nobody else's.
            assertThat(get("/v1/privacy/requests?status=open", teacherToken(cbse(), 1)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get("/v1/privacy/requests", otherParent).getBody()).isEmpty();
            assertThat(get("/v1/privacy/requests", parent).getBody()).hasSize(2);
            assertThat(post("/v1/privacy/requests/" + erasureId + "/fulfil",
                body("reason", "I would like it gone"), parent).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            var queue = get("/v1/privacy/requests?status=open", registrar);
            assertThat(queue.getBody()).extracting(r -> r.get("id").asText()).contains(erasureId);
            assertThat(get("/v1/privacy/requests/" + erasureId, registrar).getBody().get("overdue").asBoolean())
                .isFalse();

            // Past the window it is overdue, and says so.
            try {
                clock.pin(Instant.now().plus(Duration.ofDays(31)));
                assertThat(get("/v1/privacy/requests/" + erasureId, registrar).getBody()
                    .get("overdue").asBoolean()).isTrue();
            } finally {
                clock.release();
            }

            assertThat(post("/v1/privacy/requests/" + erasureId + "/fulfil", Map.of(), registrar)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            var tooSoon = post("/v1/privacy/requests/" + erasureId + "/fulfil",
                body("reason", "Family asked in writing"), registrar);
            assertThat(tooSoon.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(queryOne("SELECT first_name FROM student WHERE id = ?", String.class, child))
                .isNotEqualTo("Erased");
            assertThat(get("/v1/privacy/requests/" + erasureId, parent).getBody().get("status").asText())
                .isEqualTo("open");
        } finally {
            // The family is told no, with the reason the office gave.
            var refused = post("/v1/privacy/requests/" + erasureId + "/refuse",
                body("reason", "Child is enrolled; ask again on leaving"), registrar);
            assertThat(refused.getBody().get("status").asText()).isEqualTo("refused");
        }
        assertThat(get("/v1/privacy/requests/" + erasureId, parent).getBody().get("decisionReason").asText())
            .isEqualTo("Child is enrolled; ask again on leaving");

        // --- erasure of a child who has left: served, and what it leaves behind ---
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String admissionNo = "SEC09-" + suffix;
        String leaverEmail = "sec09-" + suffix + "@cert.test";
        UUID leaver = UUID.fromString(post("/v1/people/students", body(
            "schoolId", cbse().id(), "admissionNo", admissionNo, "firstName", "Sec09",
            "lastName", "Leaver-" + suffix, "dob", "2015-05-05", "gender", "female"),
            principalToken(cbse())).getBody().get("id").asText());
        UUID leaverGuardian = UUID.randomUUID();
        inChainDo(jdbc -> {
            jdbc.update("INSERT INTO guardian (id, school_id, first_name, last_name, phone, email) "
                + "VALUES (?, ?, 'Sec09', 'Parent', ?, ?)",
                leaverGuardian, cbse().id(), "+9188" + Math.abs(suffix.hashCode()), leaverEmail);
            jdbc.update("INSERT INTO guardian_student (guardian_id, student_id, relation, is_primary) "
                + "VALUES (?, ?, 'mother', TRUE)", leaverGuardian, leaver);
            jdbc.update("INSERT INTO user_account (school_id, subject_type, subject_id, email) "
                + "VALUES (?, 'guardian', ?, ?)", cbse().id(), leaverGuardian, leaverEmail);
        });
        String leaverParent = guardianTokenFor(cbse(), leaver);
        post("/v1/privacy/students/" + leaver + "/consents",
            body("purpose", "biometric", "granted", true, "source", "parent_app"), leaverParent);

        UUID goId = UUID.fromString(post("/v1/privacy/requests",
            body("studentId", leaver, "kind", "erasure"), leaverParent).getBody().get("id").asText());
        var served = post("/v1/privacy/requests/" + goId + "/fulfil",
            body("reason", "Left the school; identity verified at the counter"), registrar);
        assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(served.getBody().get("status").asText()).isEqualTo("fulfilled");

        // The person is gone from the row; the row, and its key, are not.
        var row = inChain(jdbc -> jdbc.queryForMap(
            "SELECT first_name, last_name, dob, gender, admission_no, erased_at FROM student WHERE id = ?",
            leaver));
        assertThat(row.get("first_name")).isEqualTo("Erased");
        assertThat(row.get("last_name")).isNull();
        assertThat(row.get("dob")).isNull();
        assertThat(row.get("gender")).isNull();
        assertThat(row.get("admission_no")).isEqualTo(admissionNo);
        assertThat(row.get("erased_at")).isNotNull();

        // A parent who was here for this child alone goes with them, and so
        // does their way in; the address is free for whoever holds it next.
        var parentRow = inChain(jdbc -> jdbc.queryForMap(
            "SELECT first_name, phone, email, erased_at FROM guardian WHERE id = ?", leaverGuardian));
        assertThat(parentRow.get("first_name")).isEqualTo("Erased");
        assertThat(parentRow.get("phone")).isNull();
        assertThat(parentRow.get("email")).isNull();
        assertThat(count("SELECT count(*) FROM user_account WHERE email = ?", leaverEmail)).isZero();
        assertThat(count("SELECT count(*) FROM user_account WHERE subject_id = ? AND is_active",
            leaverGuardian)).isZero();
        assertThat(count("SELECT count(*) FROM consent_record WHERE subject_id = ? AND revoked_at IS NULL",
            leaver)).isZero();
        // A token still in the parent's browser opens nothing.
        assertThat(get("/v1/privacy/students/" + leaver + "/consents", leaverParent).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        // Who served it and why is on the school's own record, and serving it
        // again is the same answer rather than an error.
        assertAudited("privacy.request_fulfilled", goId, cbse().registrarUserId(),
            "Left the school; identity verified at the counter");
        assertThat(post("/v1/privacy/requests/" + goId + "/fulfil",
            body("reason", "Retry after a timeout"), registrar).getBody().get("status").asText())
            .isEqualTo("fulfilled");
    }

    @Test @Tag("P1")
    void cert_SEC_10_parentSeesOnlyTheirOwnChildren() {
        var school = cbse();
        UUID section = currentFocusSection(school);
        var students = studentsIn(section);
        UUID mine = students.get(0);
        UUID somebodyElses = students.get(1);
        String parent = guardianTokenFor(school, mine);

        // The school-wide student list is not a parent's to call at all: the
        // gate is `student.view`, and a guardian holds only `student.view.own`.
        assertThat(get("/v1/people/students?schoolId=" + school.id(), parent).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        // Their own child reads; another family's child does not.
        assertThat(get("/v1/people/students/" + mine, parent).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/people/students/" + somebodyElses, parent).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/people/students/" + somebodyElses + "/guardians", parent).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/attendance/students/" + somebodyElses + "?from=2026-08-01&to=2026-08-31", parent)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The directory is the read that used to hand one parent the whole
        // school's contact list (GAP-31). A family sees staff, and no other
        // family at all.
        var directory = get("/v1/people/directory?schoolId=" + school.id(), parent);
        assertThat(directory.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(directory.getBody()).isNotEmpty();
        for (var entry : directory.getBody()) {
            assertThat(entry.get("subjectType").asText()).isEqualTo("staff");
        }

        // The staffroom's own directory is untouched — it still spans the school.
        var staffView = get("/v1/people/directory?schoolId=" + school.id(), principalToken(school)).getBody();
        assertThat(staffView.size()).isGreaterThan(directory.getBody().size());

        // Which staff a family reaches is a question about today, on both
        // halves. A teacher timetabled into the child's section is contactable
        // while that period is in force and not before or after it — the same
        // window that decides whose sections that teacher may read, so the
        // parent is never handed the address of somebody who cannot open their
        // child's record.
        // A teacher whose only route to this family is a timetabled period is
        // reachable while that period is in force, and not before or after it.
        // Every teacher in the fixture already reaches the family some other
        // way — a standing subject assignment, or `guardian.view` — so this
        // one is the scenario's own, made rather than borrowed.
        String principal = principalToken(school);
        UUID visitingTeacher = UUID.randomUUID();
        UUID visitingAccount = UUID.randomUUID();
        inChainDo(jdbc -> {
            jdbc.update(
                "INSERT INTO staff (id, school_id, employee_no, first_name, last_name, email, "
                + "employment_type, joined_on) VALUES (?, ?, 'EMP-SEC10', 'Visiting', 'Teacher', "
                + "'sec10.visiting@oakridge.test', 'visiting', current_date)",
                visitingTeacher, school.id());
            // The directory is over `user_account`: a staff row with no login
            // is not in anybody's contact list, whatever they teach.
            jdbc.update(
                "INSERT INTO user_account (id, school_id, subject_type, subject_id, email) "
                + "VALUES (?, ?, 'staff', ?, 'sec10.visiting@oakridge.test')",
                visitingAccount, school.id(), visitingTeacher);
        });
        try {
            // No period yet, so no address.
            assertThat(directoryStaffIds(school, parent)).doesNotContain(visitingTeacher);

            var running = post("/v1/timetable/slots", body(
                "sectionId", section, "subjectId", subjectOf(school, school.subjectCodes().get(0)),
                "teacherStaffId", visitingTeacher, "dayOfWeek", 1, "periodNo", 10,
                "startsAt", "17:00:00", "endsAt", "17:45:00", "room", "SEC10-NOW",
                "effectiveFrom", school.currentAy().startsOn().toString()), principal);
            assertThat(running.getStatusCode()).isEqualTo(HttpStatus.OK);
            UUID runningSlot = UUID.fromString(running.getBody().get("id").asText());
            assertThat(directoryStaffIds(school, parent)).contains(visitingTeacher);

            // Retired yesterday: the period is history, and so is the address.
            assertThat(post("/v1/timetable/slots/" + runningSlot + "/retire",
                body("lastDay", LocalDate.now().minusDays(1).toString()), principal)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(directoryStaffIds(school, parent)).doesNotContain(visitingTeacher);

            // And a period that starts next week is not an address yet.
            var later = post("/v1/timetable/slots", body(
                "sectionId", section, "subjectId", subjectOf(school, school.subjectCodes().get(0)),
                "teacherStaffId", visitingTeacher, "dayOfWeek", 1, "periodNo", 10,
                "startsAt", "17:00:00", "endsAt", "17:45:00", "room", "SEC10-LATER",
                "effectiveFrom", LocalDate.now().plusWeeks(1).toString()), principal);
            assertThat(later.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(directoryStaffIds(school, parent)).doesNotContain(visitingTeacher);
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM timetable_slot WHERE teacher_staff_id = ?", visitingTeacher);
                jdbc.update("DELETE FROM user_account WHERE id = ?", visitingAccount);
                jdbc.update("DELETE FROM staff WHERE id = ?", visitingTeacher);
            });
        }

        // The other half: a withdrawal filed today for a last day at the end of
        // the month leaves the child on the register until then, so the family
        // keeps the school's contact list for the month they most need it.
        // `status` says why the enrolment closes, never whether it is open.
        var before = directoryStaffIds(school, parent);
        assertThat(before).isNotEmpty();
        inChainDo(jdbc -> jdbc.update(
            "UPDATE enrolment SET status = 'withdrawn', ends_on = ? WHERE student_id = ? AND ends_on IS NULL",
            java.sql.Date.valueOf(LocalDate.now().plusDays(21)), mine));
        try {
            // Read as a status, this is where the teachers vanish.
            assertThat(directoryStaffIds(school, parent)).isEqualTo(before);
        } finally {
            inChainDo(jdbc -> jdbc.update(
                "UPDATE enrolment SET status = 'active', ends_on = NULL WHERE student_id = ? "
                + "AND status = 'withdrawn'", mine));
        }
        assertThat(directoryStaffIds(school, parent)).isEqualTo(before);

        // A guardian unlinked from a child loses them, without any other change.
        UUID guardianId = queryOne(
            "SELECT gs.guardian_id FROM guardian_student gs WHERE gs.student_id = ? ORDER BY gs.is_primary DESC "
            + "LIMIT 1", UUID.class, mine);
        var link = inChain(jdbc -> jdbc.queryForMap(
            "SELECT relation, is_primary, is_custodial, is_payor, is_communications_recipient "
            + "FROM guardian_student WHERE guardian_id = ? AND student_id = ?", guardianId, mine));
        inChainDo(jdbc -> jdbc.update(
            "DELETE FROM guardian_student WHERE guardian_id = ? AND student_id = ?", guardianId, mine));
        try {
            assertThat(get("/v1/people/students/" + mine, parent).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            inChainDo(jdbc -> jdbc.update(
                "INSERT INTO guardian_student (guardian_id, student_id, relation, is_primary, is_custodial, "
                + "is_payor, is_communications_recipient) VALUES (?, ?, ?, ?, ?, ?, ?)",
                guardianId, mine, link.get("relation"), link.get("is_primary"), link.get("is_custodial"),
                link.get("is_payor"), link.get("is_communications_recipient")));
        }
        assertThat(get("/v1/people/students/" + mine, parent).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- helpers

    private java.util.Set<UUID> directoryStaffIds(
        com.schoolsoft.certification.support.CertificationFixture.SchoolSeed school, String token
    ) {
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        get("/v1/people/directory?schoolId=" + school.id(), token).getBody()
            .forEach(entry -> ids.add(UUID.fromString(entry.get("subjectId").asText())));
        return ids;
    }

    private static final String OTHER_CHAIN = "certother";

    /** Signed with the suite's configured secret, but already past its expiry. */
    private String expiredAccessToken(UUID userAccountId) {
        return io.jsonwebtoken.Jwts.builder()
            .subject(userAccountId.toString())
            .claims(Map.of("cid", seed.chainId().toString(), "cs", seed.chainSchema(),
                "sid", cbse().id().toString(), "st", "staff", "typ", "access"))
            .issuedAt(java.util.Date.from(java.time.Instant.now().minusSeconds(7200)))
            .expiration(java.util.Date.from(java.time.Instant.now().minusSeconds(3600)))
            .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                "certification-suite-secret-certification-suite-secret"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .compact();
    }

    private void provisionOtherChain() {
        provisioning.provision(OTHER_CHAIN, "Other Chain", "starter");
    }
}
