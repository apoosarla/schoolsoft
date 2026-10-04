package com.schoolsoft.iam.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * A sliding-window limit on the sign-in endpoints.
 *
 * <p>A code is six digits, so without a limit it is guessed at the speed of
 * the network. The caller decides what counts: a request for a code always
 * does, a verification only when it fails, so somebody signing in correctly
 * all day is never locked out by it.</p>
 *
 * <p>Keyed twice by the caller, per account and per address. Per account
 * alone is not enough: a guess is checked before the account is looked up, so
 * a code can be tried against a name that does not exist, and a fresh name
 * per guess would never reach an account's limit.</p>
 *
 * <p>In memory, per API node — the same bound and the same upgrade path as
 * {@code PublicRateLimiter}.</p>
 */
@Component
public class OtpThrottle {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    public OtpThrottle(Clock clock) { this.clock = clock; }

    /** Refuses with 429 once {@code key} has {@code limit} hits in the window. Counts nothing. */
    public void refuseIfOver(String key, int limit) {
        Deque<Instant> window = hits.get(key);
        if (window == null) return;
        Instant now = clock.instant();
        synchronized (window) {
            prune(window, now);
            if (window.size() >= limit) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Wait a few minutes and try again.");
            }
        }
    }

    /** Counts one hit against {@code key}. */
    public void record(String key) {
        Instant now = clock.instant();
        Deque<Instant> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            prune(window, now);
            window.addLast(now);
        }
        if (hits.size() > 50_000) hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now);
                return e.getValue().isEmpty();
            }
        });
    }

    private static void prune(Deque<Instant> window, Instant now) {
        while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) window.pollFirst();
    }
}
