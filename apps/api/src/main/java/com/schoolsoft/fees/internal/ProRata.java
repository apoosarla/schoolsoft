package com.schoolsoft.fees.internal;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * What share of a billing cycle a child who joined partway through owes
 * (ADM-16), with nothing read from the database.
 *
 * The unit is the whole month, and the joining month counts: a child who
 * arrives on the 5th or the 25th of October owes October. A school quotes its
 * fees by the month and a parent checks the bill by counting them, so the
 * share is one both sides can arrive at on their fingers — a working-day
 * fraction would be more exact and would change every time a holiday was
 * declared.
 *
 * Only a recurring head is shared out. A one-time head (admission, an annual
 * charge) is owed whole by whoever is billed it; {@link FeeGenerationService}
 * decides which is which from {@code fee_head.is_recurring}.
 */
final class ProRata {

    private ProRata() {}

    /** {@code owed} months out of the cycle's {@code of}. */
    record Share(int owed, int of) {

        static final Share WHOLE = new Share(1, 1);

        boolean whole() { return owed == of; }

        /** Joined after the cycle was over: nothing in it is theirs to pay. */
        boolean nothing() { return owed == 0; }

        double of(double amount) {
            return whole() ? amount : InvoicePricing.round(amount * owed / of);
        }

        /** What the invoice line says, so the reduced figure explains itself. */
        String label() {
            return owed + " of " + of + " months";
        }
    }

    /**
     * The share of {@code periodStart..periodEnd} owed by a child on the
     * register from {@code joinedOn}. A cycle with no period is billed whole:
     * that is every run made before cycles carried one.
     */
    static Share months(LocalDate periodStart, LocalDate periodEnd, LocalDate joinedOn) {
        if (periodStart == null || periodEnd == null) return Share.WHOLE;
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("The billing period ends before it starts");
        }
        YearMonth first = YearMonth.from(periodStart);
        YearMonth last = YearMonth.from(periodEnd);
        int of = (int) ChronoUnit.MONTHS.between(first, last) + 1;
        if (joinedOn == null || !joinedOn.isAfter(periodStart)) return new Share(of, of);
        if (joinedOn.isAfter(periodEnd)) return new Share(0, of);
        int owed = (int) ChronoUnit.MONTHS.between(YearMonth.from(joinedOn), last) + 1;
        return new Share(owed, of);
    }
}
