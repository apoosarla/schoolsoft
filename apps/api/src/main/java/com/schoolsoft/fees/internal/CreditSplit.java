package com.schoolsoft.fees.internal;

/**
 * Where a credit note or waiver lands, with nothing read from the database.
 *
 * Taking money off a bill lowers what is owed first. Whatever is left of it
 * falls on money the family has already paid, and that part is not lost: it
 * stops being payment of this bill and becomes credit the school holds for
 * them, the same advance an overpayment leaves, there to be refunded or set
 * against the next bill. So an invoice never shows more paid than billed, and
 * the ledger moves by exactly what the invoice does.
 *
 * {@link FeeAdjustmentService} reads the invoice and posts the legs; this does
 * the sums (see {@code CreditSplitTest}).
 */
final class CreditSplit {

    private CreditSplit() {}

    /** {@code offDues} comes off the receivable; {@code toAdvance} moves from paid to credit held. */
    record Split(double offDues, double toAdvance) {}

    /**
     * @throws IllegalArgumentException when {@code amount} is more than the
     *     invoice was billed for — nobody can be credited a fee they were
     *     never charged
     */
    static Split of(double total, double paid, double amount) {
        if (amount > total + 0.005) {
            throw new IllegalArgumentException("more than was billed");
        }
        double offDues = round(Math.min(amount, Math.max(0, total - paid)));
        return new Split(offDues, round(amount - offDues));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
