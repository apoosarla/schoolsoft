package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
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
    @Disabled("GAP-27 — AuditService.record exists but no high-risk mutation calls it: mark unlock, fee "
        + "waiver, enrolment status change and role grant all write without an audit row (Phase 3).")
    void cert_SEC_08_highRiskMutationsAreAuditLogged() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-23 — consent_record exists, but there is no export, erasure or retention path to serve "
        + "a DPDP request (Phase 8).")
    void cert_SEC_09_dpdpConsentExportAndErasureAreServable() {
    }

    @Test @Tag("P1")
    @Disabled("Nothing scopes a guardian to their own children: /v1/people/students returns every student "
        + "in the school for any authenticated caller, guardians included. New gap found in Phase 0 — "
        + "security-relevant.")
    void cert_SEC_10_parentSeesOnlyTheirOwnChildren() {
    }

    // ---------------------------------------------------------------- helpers

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
