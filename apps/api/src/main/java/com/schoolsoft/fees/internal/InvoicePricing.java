package com.schoolsoft.fees.internal;

import java.util.List;

/**
 * The arithmetic of one generated invoice, with nothing read from the database.
 *
 * {@link FeeGenerationService} gathers the charges, concessions and sibling
 * percentage; this prices them. It lives apart so the sums — where a rounding
 * or ordering slip bills two thousand families wrongly — can be tested without
 * Postgres (see {@code InvoicePricingTest}).
 *
 * Every line is priced on its own: discounts come off the head's amount and can
 * never take it below zero, and GST is charged on what is left (FEE-13), rounded
 * per line so the lines a parent reads add up to the tax on the invoice.
 */
final class InvoicePricing {

    private InvoicePricing() {}

    /** One {@code fee_concession} row: a flat amount off, a percentage off, or both. */
    record Concession(Double flatAmount, Double pct) {}

    record PricedLine(double amount, double discount, double net, double gst) {}

    record Priced(List<PricedLine> lines, double subtotal, double gst, double total) {}

    /** A student's own concessions against a head of {@code amount} (FEE-03). */
    static double concessionOff(double amount, List<Concession> concessions) {
        double off = 0;
        for (Concession c : concessions) {
            off += (c.flatAmount() == null ? 0 : c.flatAmount())
                + (c.pct() == null ? 0 : c.pct()) * amount / 100.0;
        }
        return round(off);
    }

    /** What the sibling policy's percentage takes off a head of {@code amount} (FEE-04). */
    static double siblingOff(double amount, double pct) {
        return pct == 0 ? 0 : round(amount * pct / 100.0);
    }

    /** A head with its concessions applied and its GST on the net. */
    static PricedLine line(double amount, double gstRatePct, double concession, double sibling) {
        double discount = Math.min(concession + sibling, amount);
        double net = amount - discount;
        return new PricedLine(amount, discount, net, round(net * gstRatePct / 100.0));
    }

    static Priced invoice(List<PricedLine> lines) {
        double subtotal = 0;
        double gst = 0;
        for (PricedLine l : lines) {
            subtotal += l.net();
            gst += l.gst();
        }
        return new Priced(lines, round(subtotal), round(gst), round(subtotal + gst));
    }

    static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
