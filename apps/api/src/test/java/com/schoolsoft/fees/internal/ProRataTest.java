package com.schoolsoft.fees.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schoolsoft.fees.internal.ProRata.Share;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A mid-year joiner's share of a cycle, without Spring or Postgres. The
 * invoice it lands on is ADM-16 in the certification suite.
 */
@Tag("unit")
class ProRataTest {

    private static final LocalDate JUL_1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate DEC_31 = LocalDate.of(2026, 12, 31);

    @Test
    void a_child_there_from_the_start_owes_the_whole_cycle() {
        Share share = ProRata.months(JUL_1, DEC_31, LocalDate.of(2026, 4, 1));

        assertThat(share.whole()).isTrue();
        assertThat(share.of(60_000)).isEqualTo(60_000);
    }

    @Test
    void joining_on_the_first_day_of_the_cycle_is_not_joining_late() {
        assertThat(ProRata.months(JUL_1, DEC_31, JUL_1).whole()).isTrue();
    }

    @Test
    void an_october_joiner_owes_october_to_december() {
        Share share = ProRata.months(JUL_1, DEC_31, LocalDate.of(2026, 10, 5));

        assertThat(share).isEqualTo(new Share(3, 6));
        assertThat(share.of(60_000)).isEqualTo(30_000);
        assertThat(share.label()).isEqualTo("3 of 6 months");
    }

    @Test
    void the_joining_month_counts_however_late_in_it_the_child_arrives() {
        assertThat(ProRata.months(JUL_1, DEC_31, LocalDate.of(2026, 10, 1)))
            .isEqualTo(ProRata.months(JUL_1, DEC_31, LocalDate.of(2026, 10, 31)));
    }

    @Test
    void joining_later_in_the_first_month_still_owes_every_month() {
        assertThat(ProRata.months(JUL_1, DEC_31, LocalDate.of(2026, 7, 20)).whole()).isTrue();
    }

    @Test
    void a_cycle_that_crosses_the_new_year_counts_months_across_it() {
        Share share = ProRata.months(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 3, 31),
            LocalDate.of(2027, 1, 12));

        assertThat(share).isEqualTo(new Share(3, 6));
    }

    @Test
    void a_share_that_does_not_divide_evenly_is_rounded_to_the_paisa() {
        Share share = ProRata.months(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31),
            LocalDate.of(2026, 11, 3));

        assertThat(share.of(10_000)).isEqualTo(6_666.67);
    }

    @Test
    void a_child_who_joined_after_the_cycle_ended_owes_none_of_it() {
        Share share = ProRata.months(JUL_1, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 5));

        assertThat(share.nothing()).isTrue();
        assertThat(share.of(60_000)).isZero();
    }

    @Test
    void a_cycle_with_no_period_is_billed_whole() {
        assertThat(ProRata.months(null, null, LocalDate.of(2026, 10, 5)).whole()).isTrue();
    }

    @Test
    void a_period_that_ends_before_it_starts_is_refused() {
        assertThatThrownBy(() -> ProRata.months(DEC_31, JUL_1, JUL_1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
