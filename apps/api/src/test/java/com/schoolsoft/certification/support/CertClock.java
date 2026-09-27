package com.schoolsoft.certification.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The suite's clock: the real one until a scenario pins an instant, and the
 * real one again once it lets go. Scenarios share one Spring context, so a
 * scenario that pins must {@link #release} in a {@code finally}.
 *
 * <p>Only what goes through {@code SchoolClock} moves. Postgres's own
 * {@code now()} does not, which is why the API binds today rather than writing
 * {@code CURRENT_DATE}.
 */
public final class CertClock extends Clock {

    private volatile Instant pinned;

    public void pin(Instant instant) { this.pinned = instant; }

    public void release() { this.pinned = null; }

    @Override
    public Instant instant() {
        Instant at = pinned;
        return at != null ? at : Instant.now();
    }

    @Override
    public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override
    public Clock withZone(ZoneId zone) {
        CertClock source = this;
        return new Clock() {
            @Override public Instant instant() { return source.instant(); }
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId other) { return source.withZone(other); }
        };
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public CertClock certClock() { return new CertClock(); }
    }
}
