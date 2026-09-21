package com.schoolsoft.iam.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.iam.internal.OtpStore;
import com.schoolsoft.iam.internal.UserLookupService;
import com.schoolsoft.platform.security.JwtService;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.platform.web.NotFoundException;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public auth endpoints. Reachable without a JWT (whitelisted in
 * {@link com.schoolsoft.platform.security.SecurityConfig}).
 *
 * Flow per design §16 — OTP for parents (phone) and staff (email).
 *  1. POST /v1/auth/otp/start  {identifier, chain_slug?}  → 200 (OTP delivered via NOTIF)
 *  2. POST /v1/auth/otp/verify {identifier, chain_slug?, code} → access+refresh
 *
 * The MVP OTP store is in-memory (Redis-backed in prod). On verify we look up
 * the user_account row inside the resolved chain to bind the JWT to a school.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final OtpStore otpStore;
    private final UserLookupService lookup;
    private final JwtService jwt;

    public AuthController(OtpStore otpStore, UserLookupService lookup, JwtService jwt) {
        this.otpStore = otpStore;
        this.lookup = lookup;
        this.jwt = jwt;
    }

    public record OtpStartRequest(@NotBlank String identifier, String chainSlug) {}
    public record OtpVerifyRequest(@NotBlank String identifier, String chainSlug, @NotBlank String code) {}
    public record AuthResponse(String accessToken, String refreshToken, Map<String, Object> profile) {}

    private static final String PLATFORM_OTP_NAMESPACE = "platform";

    @PreAuthorize("permitAll()")
    @PostMapping("/otp/start")
    public ResponseEntity<Map<String, Object>> start(@RequestBody OtpStartRequest req) {
        String code = otpStore.issue(req.identifier(), req.chainSlug());
        // In prod this hands off to the notification module; for dev we echo the
        // code into logs to make the flow self-serve.
        log.info("[dev] OTP for {} (chain={}): {}", req.identifier(), req.chainSlug(), code);
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/otp/verify")
    public ResponseEntity<AuthResponse> verify(@RequestBody OtpVerifyRequest req) {
        if (!otpStore.verify(req.identifier(), req.chainSlug(), req.code())) {
            return ResponseEntity.status(401).build();
        }
        var resolved = lookup.resolve(req.identifier(), req.chainSlug())
            .orElseThrow(() -> new NotFoundException("No account for " + req.identifier()));
        refuseUntilTheSchoolIsOpen(resolved);

        String access = jwt.issueAccess(
            resolved.userAccountId(),
            resolved.chainId() == null ? "platform" : resolved.chainId().toString(),
            resolved.chainSchema(),
            resolved.schoolId(),
            resolved.subjectType()
        );
        String refresh = jwt.issueRefresh(
            resolved.userAccountId(),
            resolved.chainId() == null ? "platform" : resolved.chainId().toString(),
            resolved.chainSchema()
        );
        return ResponseEntity.ok(new AuthResponse(access, refresh, Map.of(
            "userAccountId", resolved.userAccountId(),
            "subjectType",   resolved.subjectType(),
            "schoolId",      resolved.schoolId() == null ? "" : resolved.schoolId().toString(),
            "chainSchema",   resolved.chainSchema()
        )));
    }

    // -------------------------- Platform-admin OTP --------------------------
    // Separate from the chain OTP flow above: platform_admin accounts live in
    // platform.platform_user, not any chain's user_account, and there's no
    // chain to scan — resolution goes straight to UserLookupService.resolvePlatformAdmin.
    // See BACKLOG.md — this replaces the HQ Console's paste-a-bearer-token workaround.

    public record PlatformOtpStartRequest(@NotBlank String email) {}
    public record PlatformOtpVerifyRequest(@NotBlank String email, @NotBlank String code) {}

    @PreAuthorize("permitAll()")
    @PostMapping("/platform-admin/otp/start")
    public ResponseEntity<Map<String, Object>> platformStart(@RequestBody PlatformOtpStartRequest req) {
        String code = otpStore.issue(req.email(), PLATFORM_OTP_NAMESPACE);
        log.info("[dev] Platform-admin OTP for {}: {}", req.email(), code);
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/platform-admin/otp/verify")
    public ResponseEntity<AuthResponse> platformVerify(@RequestBody PlatformOtpVerifyRequest req) {
        if (!otpStore.verify(req.email(), PLATFORM_OTP_NAMESPACE, req.code())) {
            return ResponseEntity.status(401).build();
        }
        var resolved = lookup.resolvePlatformAdmin(req.email())
            .orElseThrow(() -> new NotFoundException("No platform-admin account for " + req.email()));

        String access = jwt.issueAccess(resolved.userAccountId(), "platform", "platform", null, "platform_admin");
        String refresh = jwt.issueRefresh(resolved.userAccountId(), "platform", "platform");
        return ResponseEntity.ok(new AuthResponse(access, refresh, Map.of(
            "userAccountId", resolved.userAccountId(),
            "subjectType",   "platform_admin"
        )));
    }

    /**
     * A school that has not opened yet is the office's to work in and nobody
     * else's (V036). The people building it sign in; the families and
     * students it will serve cannot, because a half-built school shows a
     * parent an empty timetable, an empty fee ledger and a child who appears
     * to be on no register — and none of that says "not yet".
     *
     * <p>Applied at the door rather than on every request: a school moves
     * from draft to live and, short of an operator suspending it, not back,
     * so a token in a family's hands cannot go stale this way. Checking here
     * and on the refresh costs one join on a read that was already being
     * made; checking on every request would cost a query per request for a
     * transition that happens once in a school's life.</p>
     *
     * <p>An account with no school — a chain admin — is not a family and has
     * no school to be closed out of, so it passes.</p>
     */
    private void refuseUntilTheSchoolIsOpen(UserLookupService.Resolved resolved) {
        boolean family = "guardian".equals(resolved.subjectType())
            || "student".equals(resolved.subjectType());
        String lifecycle = resolved.schoolLifecycle();
        if (!family || lifecycle == null || "live".equals(lifecycle)) return;
        throw new ForbiddenException("draft".equals(lifecycle)
            ? "This school has not opened yet. While it is being set up only its own staff can "
              + "sign in; the school will tell you when it is ready."
            : "This school is not open. Please contact the school office.");
    }

    @PreAuthorize("permitAll()")
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody Map<String, String> body) {
        String token = body.get("refreshToken");
        if (token == null) return ResponseEntity.badRequest().build();
        var claims = jwt.parse(token);
        if (!"refresh".equals(claims.get("typ"))) return ResponseEntity.status(401).build();

        UUID sub = UUID.fromString(claims.getSubject());
        String cid = claims.get("cid", String.class);
        String cs  = claims.get("cs", String.class);
        var resolved = lookup.resolveById(sub, cs);
        if (resolved.isEmpty()) return ResponseEntity.status(401).build();
        var r = resolved.get();
        // Also on the refresh: a token minted before the school closed must
        // not outlive it by the fifteen minutes an access token is good for.
        refuseUntilTheSchoolIsOpen(r);

        String access = jwt.issueAccess(sub, cid, cs, r.schoolId(), r.subjectType());
        return ResponseEntity.ok(new AuthResponse(access, token, Map.of()));
    }
}
