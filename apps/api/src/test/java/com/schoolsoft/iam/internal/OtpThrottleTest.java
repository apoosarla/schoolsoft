package com.schoolsoft.iam.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class OtpThrottleTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-04T12:00:00Z"));
    private final OtpThrottle throttle = new OtpThrottle(clock);

    @Test
    void asking_counts_nothing() {
        for (int i = 0; i < 50; i++) throttle.refuseIfOver("a", 3);
        assertThatCode(() -> throttle.refuseIfOver("a", 3)).doesNotThrowAnyException();
    }

    @Test
    void refuses_once_the_limit_is_reached_and_not_before() {
        throttle.record("a");
        throttle.record("a");
        assertThatCode(() -> throttle.refuseIfOver("a", 3)).doesNotThrowAnyException();

        throttle.record("a");
        assertThatThrownBy(() -> throttle.refuseIfOver("a", 3))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("429");
    }

    @Test
    void one_key_does_not_spend_another() {
        for (int i = 0; i < 3; i++) throttle.record("a");
        assertThatCode(() -> throttle.refuseIfOver("b", 3)).doesNotThrowAnyException();
    }

    @Test
    void a_hit_stops_counting_when_it_leaves_the_window() {
        for (int i = 0; i < 3; i++) throttle.record("a");
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThatCode(() -> throttle.refuseIfOver("a", 3)).doesNotThrowAnyException();
    }

    private static final class MovableClock extends Clock {
        private Instant now;
        MovableClock(Instant start) { this.now = start; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
