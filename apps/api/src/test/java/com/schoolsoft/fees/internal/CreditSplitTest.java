package com.schoolsoft.fees.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schoolsoft.fees.internal.CreditSplit.Split;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Where a credit note lands, without Spring or Postgres. The posting itself —
 * the ledger legs, the refund that follows — is FEE-11 in the certification
 * suite.
 */
@Tag("unit")
class CreditSplitTest {

    @Test
    void a_credit_within_what_is_owed_only_lowers_the_dues() {
        Split split = CreditSplit.of(5_250, 2_000, 1_000);

        assertThat(split.offDues()).isEqualTo(1_000);
        assertThat(split.toAdvance()).isZero();
    }

    @Test
    void a_credit_past_what_is_owed_turns_the_rest_into_credit_held() {
        Split split = CreditSplit.of(5_250, 2_000, 4_000);

        assertThat(split.offDues()).isEqualTo(3_250);
        assertThat(split.toAdvance()).isEqualTo(750);
    }

    @Test
    void a_credit_on_a_bill_paid_in_full_is_all_credit_held() {
        // A child withdrawn mid-term after the term was paid for.
        Split split = CreditSplit.of(12_000, 12_000, 6_000);

        assertThat(split.offDues()).isZero();
        assertThat(split.toAdvance()).isEqualTo(6_000);
    }

    @Test
    void the_whole_bill_can_be_credited() {
        Split split = CreditSplit.of(5_250, 2_000, 5_250);

        assertThat(split.offDues()).isEqualTo(3_250);
        assertThat(split.toAdvance()).isEqualTo(2_000);
    }

    @Test
    void more_than_was_billed_is_refused() {
        assertThatThrownBy(() -> CreditSplit.of(5_250, 2_000, 5_250.01))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void paise_do_not_drift() {
        Split split = CreditSplit.of(100.10, 33.33, 80.05);

        assertThat(split.offDues()).isEqualTo(66.77);
        assertThat(split.toAdvance()).isEqualTo(13.28);
    }
}
