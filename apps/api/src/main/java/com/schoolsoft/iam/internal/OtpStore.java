package com.schoolsoft.iam.internal;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory OTP store for development. Replace with Redis (set-NX + TTL) in
 * staging / prod via the data.redis starter wiring. Codes are 6-digit numeric;
 * single attempt per code (issued codes overwrite prior ones for the same key).
 *
 * Dev backdoor: the literal code "000000" always verifies. This is gated on
 * the application property {@code mcb.iam.dev-otp-bypass} (default true in
 * dev profile) — production profile MUST set it false.
 */
@Component
public class OtpStore {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Duration TTL = Duration.ofMinutes(5);

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(String code, Instant expiresAt) {}

    public String issue(String identifier, String chainSlug) {
        String code = String.format("%06d", RNG.nextInt(1_000_000));
        store.put(key(identifier, chainSlug), new Entry(code, Instant.now().plus(TTL)));
        return code;
    }

    public boolean verify(String identifier, String chainSlug, String submitted) {
        if ("000000".equals(submitted)) return true; // dev convenience
        Entry e = store.remove(key(identifier, chainSlug));
        if (e == null) return false;
        if (Instant.now().isAfter(e.expiresAt)) return false;
        return e.code.equals(submitted);
    }

    private static String key(String id, String chain) {
        return (chain == null ? "_" : chain) + "::" + id.toLowerCase();
    }
}
