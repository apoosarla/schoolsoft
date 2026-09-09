package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-SEC — roles, access & security. */
class SecurityCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    @Disabled("A used OTP is consumed and a wrong code is refused, but there is no rate limiting on "
        + "/v1/auth/otp/verify and OtpStore accepts the literal code 000000 unconditionally — the dev "
        + "bypass is not gated on a profile or property despite its own doc comment. New gap found in "
        + "Phase 0 — security-relevant.")
    void cert_SEC_01_otpLoginRejectsExpiredReusedAndBruteForcedCodes() {
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

        // An access token presented to the refresh endpoint is refused.
        var wrongType = post("/v1/auth/refresh", Map.of("refreshToken", principalToken(cbse())), null);
        assertThat(wrongType.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test @Tag("P1")
    @Disabled("/v1/iam/me/screens reports the caller's screens, but no endpoint checks them: a "
        + "hand-crafted call to any module succeeds for any authenticated staff account regardless of "
        + "role. Screen access is advisory only. New gap found in Phase 0 — security-relevant.")
    void cert_SEC_03_screenAccessIsEnforcedServerSide() {
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

    @Test @Tag("P1")
    void cert_SEC_05_chainAdminReadsAcrossTheChainButNotAnotherChain() {
        var schools = get("/v1/tenancy/schools", chainAdminToken()).getBody();
        assertThat(schools).hasSize(2);
        assertThat(get("/v1/enrolment/sections/" + currentFocusSection(cbse()), chainAdminToken()).getBody())
            .isNotEmpty();
        assertThat(get("/v1/enrolment/sections/" + currentFocusSection(cie()), chainAdminToken()).getBody())
            .isNotEmpty();

        // A token minted for a different chain resolves against that chain's schema, which has no
        // access to this chain's rows — the isolation is the schema, not a filter.
        provisionOtherChain();
        UUID otherChainId = platformJdbc.queryForObject(
            "SELECT id FROM platform.chain WHERE slug = ?", UUID.class, OTHER_CHAIN);
        String otherChainToken = jwt.issueAccess(UUID.randomUUID(), otherChainId.toString(),
            "chain_" + OTHER_CHAIN, null, "chain_admin");
        assertThat(get("/v1/tenancy/schools", otherChainToken).getBody()).isEmpty();
        assertThat(get("/v1/enrolment/sections/" + currentFocusSection(cbse()), otherChainToken).getBody())
            .isEmpty();
    }

    @Test @Tag("P1")
    @Disabled("Platform-admin actions authenticate separately (platform.platform_user + its own OTP flow) "
        + "but write no audit trail: audit_log lives in the chain schema and ChainAdminController records "
        + "nothing. New gap found in Phase 0 — security-relevant.")
    void cert_SEC_06_platformAdminActionsAreSeparatelyAuthenticatedAndAudited() {
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

    @Test @Tag("P1")
    @Disabled("GAP-23 — consent_record exists, but there is no export, erasure or retention path to serve "
        + "a DPDP request (Phase 8).")
    void cert_SEC_09_dpdpConsentExportAndErasureAreServable() {
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
