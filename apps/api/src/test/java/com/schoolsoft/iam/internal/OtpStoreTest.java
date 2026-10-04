package com.schoolsoft.iam.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class OtpStoreTest {

    private static final Instant NOON = Instant.parse("2026-10-04T12:00:00Z");

    private static Clock at(Instant instant) { return Clock.fixed(instant, ZoneOffset.UTC); }

    @Test
    void an_issued_code_verifies_once() {
        OtpStore store = new OtpStore(at(NOON), "", false);
        String code = store.issue("Parent@Example.test", "oak");

        assertThat(store.verify("parent@example.test", "oak", code)).isTrue();
        assertThat(store.verify("parent@example.test", "oak", code)).isFalse();
    }

    @Test
    void a_wrong_guess_spends_the_code_it_was_guessing_at() {
        OtpStore store = new OtpStore(at(NOON), "", false);
        String code = store.issue("parent@example.test", "oak");
        String wrong = code.equals("111111") ? "222222" : "111111";

        assertThat(store.verify("parent@example.test", "oak", wrong)).isFalse();
        assertThat(store.verify("parent@example.test", "oak", code)).isFalse();
    }

    @Test
    void a_code_is_good_for_five_minutes_and_no_longer() {
        var clock = new MovableClock(NOON);
        OtpStore store = new OtpStore(clock, "", false);

        String inTime = store.issue("parent@example.test", "oak");
        clock.advance(Duration.ofMinutes(5));
        assertThat(store.verify("parent@example.test", "oak", inTime)).isTrue();

        String late = store.issue("parent@example.test", "oak");
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(store.verify("parent@example.test", "oak", late)).isFalse();
    }

    @Test
    void a_code_is_for_one_account_in_one_chain() {
        OtpStore store = new OtpStore(at(NOON), "", false);
        String code = store.issue("parent@example.test", "oak");

        assertThat(store.verify("other@example.test", "oak", code)).isFalse();
        assertThat(store.verify("parent@example.test", "elm", code)).isFalse();
        assertThat(store.verifyPlatformAdmin("parent@example.test", code)).isFalse();
    }

    @Test
    void with_no_dev_code_configured_nothing_verifies_unissued() {
        OtpStore store = new OtpStore(at(NOON), "", true);

        assertThat(store.verify("parent@example.test", "oak", "000000")).isFalse();
        assertThat(store.verify("parent@example.test", "oak", "")).isFalse();
        assertThat(store.verify("parent@example.test", "oak", null)).isFalse();
        assertThat(store.verifyPlatformAdmin("ops@example.test", "000000")).isFalse();
    }

    @Test
    void the_dev_code_opens_a_chain_account_and_only_that_code_does() {
        OtpStore store = new OtpStore(at(NOON), "483920", false);

        assertThat(store.verify("parent@example.test", "oak", "483920")).isTrue();
        assertThat(store.verify("parent@example.test", "oak", "000000")).isFalse();
    }

    @Test
    void the_dev_code_opens_a_platform_admin_only_when_told_it_may() {
        assertThat(new OtpStore(at(NOON), "483920", false).verifyPlatformAdmin("ops@example.test", "483920")).isFalse();
        assertThat(new OtpStore(at(NOON), "483920", true).verifyPlatformAdmin("ops@example.test", "483920")).isTrue();
    }

    @Test
    void a_platform_admin_still_signs_in_with_an_issued_code_when_the_dev_code_is_withheld() {
        OtpStore store = new OtpStore(at(NOON), "483920", false);
        String code = store.issueForPlatformAdmin("ops@example.test");

        assertThat(store.verifyPlatformAdmin("ops@example.test", code)).isTrue();
    }

    private static final class MovableClock extends Clock {
        private Instant now;
        MovableClock(Instant start) { this.now = start; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
