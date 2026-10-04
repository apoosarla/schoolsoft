package com.schoolsoft.iam.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory OTP store for development. Replace with Redis (set-NX + TTL) in
 * staging / prod via the data.redis starter wiring. Codes are 6-digit numeric;
 * single attempt per code (issued codes overwrite prior ones for the same key,
 * and a wrong guess spends the code it was guessing at).
 *
 * <p>Dev backdoor: {@code schoolsoft.iam.dev-otp-code} is a code that always
 * verifies, because nothing delivers a real one yet. Unset or blank, there is
 * no backdoor — that is the default of the class, and {@code application.yml}
 * is what turns it on for a developer's machine. Production MUST leave
 * {@code SCHOOLSOFT_DEV_OTP_CODE} empty.</p>
 *
 * <p>{@code schoolsoft.iam.dev-otp-covers-platform} says whether the backdoor
 * also opens a platform admin's account. A stack shared with people outside
 * hands them the code so they can sign in to a school; it must not hand them
 * the operator console for every chain along with it, and the API they were
 * given serves both doors.</p>
 */
@Component
public class OtpStore {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Duration TTL = Duration.ofMinutes(5);
    /** Not a legal chain slug, so no chain can share a key with the platform's own accounts. */
    private static final String PLATFORM = "*platform";

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Clock clock;
    private final String devCode;
    private final boolean devCodeCoversPlatform;

    private record Entry(String code, Instant expiresAt) {}

    public OtpStore(
        Clock clock,
        @Value("${schoolsoft.iam.dev-otp-code:}") String devCode,
        @Value("${schoolsoft.iam.dev-otp-covers-platform:false}") boolean devCodeCoversPlatform
    ) {
        this.clock = clock;
        this.devCode = devCode == null ? "" : devCode.trim();
        this.devCodeCoversPlatform = devCodeCoversPlatform;
    }

    public String issue(String identifier, String chainSlug) {
        return issueFor(key(identifier, chainSlug));
    }

    public String issueForPlatformAdmin(String email) {
        return issueFor(key(email, PLATFORM));
    }

    public boolean verify(String identifier, String chainSlug, String submitted) {
        return isDevCode(submitted) || redeem(key(identifier, chainSlug), submitted);
    }

    public boolean verifyPlatformAdmin(String email, String submitted) {
        return (devCodeCoversPlatform && isDevCode(submitted)) || redeem(key(email, PLATFORM), submitted);
    }

    private String issueFor(String key) {
        Instant now = clock.instant();
        // Codes nobody came back for: without this an anonymous caller grows the map without bound.
        if (store.size() > 10_000) store.values().removeIf(e -> now.isAfter(e.expiresAt));
        String code = String.format("%06d", RNG.nextInt(1_000_000));
        store.put(key, new Entry(code, now.plus(TTL)));
        return code;
    }

    private boolean redeem(String key, String submitted) {
        Entry e = store.remove(key);
        if (e == null) return false;
        if (clock.instant().isAfter(e.expiresAt)) return false;
        return same(e.code, submitted);
    }

    private boolean isDevCode(String submitted) {
        return !devCode.isEmpty() && same(devCode, submitted);
    }

    /** Constant-time: how long a comparison takes must not say how much of the code was right. */
    private static boolean same(String expected, String submitted) {
        if (submitted == null) return false;
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), submitted.getBytes(StandardCharsets.UTF_8));
    }

    private static String key(String id, String chain) {
        return (chain == null ? "_" : chain) + "::" + id.toLowerCase();
    }
}
