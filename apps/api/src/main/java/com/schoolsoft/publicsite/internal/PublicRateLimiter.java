package com.schoolsoft.publicsite.internal;

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
 * A sliding-window limit on the anonymous admissions endpoints.
 *
 * <p>Tracking an application is gated by its number and the guardian's phone,
 * and application numbers are issued in sequence, so the phone is the only
 * secret — and without a limit it can be guessed at the speed of the network.
 * Lodging an application is limited for the same reason a contact form is.</p>
 *
 * <p>In memory, per API node. That bounds an attacker to the limit times the
 * number of nodes, which is the point; a shared store (Redis) is the upgrade
 * when there is more than a handful of nodes.</p>
 */
@Component
public class PublicRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    public PublicRateLimiter() { this(Clock.systemUTC()); }

    PublicRateLimiter(Clock clock) { this.clock = clock; }

    /** Counts one hit against {@code key}; refuses with 429 once {@code limit} is passed in the window. */
    public void check(String key, int limit) {
        Instant now = clock.instant();
        Deque<Instant> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) window.pollFirst();
            if (window.size() >= limit) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts. Wait a few minutes and try again.");
            }
            window.addLast(now);
        }
        if (hits.size() > 50_000) hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(now.minus(WINDOW));
            }
        });
    }
}
