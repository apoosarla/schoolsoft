package com.schoolsoft.fees.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.fees.internal.InvoicePricing.Concession;
import com.schoolsoft.fees.internal.InvoicePricing.PricedLine;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The invoice arithmetic, without Spring or Postgres. The generation run
 * itself — who is billed, which heads, idempotency — is FEE-02 and its
 * neighbours in the certification suite.
 */
@Tag("unit")
class InvoicePricingTest {

    @Test
    void a_head_with_nothing_off_is_billed_in_full_with_gst_on_the_amount() {
        PricedLine line = InvoicePricing.line(10_000, 18, 0, 0);

        assertThat(line.discount()).isZero();
        assertThat(line.net()).isEqualTo(10_000);
        assertThat(line.gst()).isEqualTo(1_800);
    }

    @Test
    void gst_is_charged_on_what_is_left_after_discounts() {
        PricedLine line = InvoicePricing.line(10_000, 18, 1_000, 500);

        assertThat(line.discount()).isEqualTo(1_500);
        assertThat(line.net()).isEqualTo(8_500);
        assertThat(line.gst()).isEqualTo(1_530);
    }

    @Test
    void discounts_never_take_a_head_below_zero() {
        // A full scholarship plus a sibling concession on top of it.
        PricedLine line = InvoicePricing.line(10_000, 18, 10_000, 1_000);

        assertThat(line.discount()).isEqualTo(10_000);
        assertThat(line.net()).isZero();
        assertThat(line.gst()).isZero();
    }

    @Test
    void a_students_concessions_add_up_flat_and_percentage_alike() {
        double off = InvoicePricing.concessionOff(12_000, List.of(
            new Concession(500.0, null),
            new Concession(null, 25.0),
            new Concession(200.0, 10.0)));

        assertThat(off).isEqualTo(500 + 3_000 + 200 + 1_200);
    }

    @Test
    void no_concessions_is_nothing_off() {
        assertThat(InvoicePricing.concessionOff(12_000, List.of())).isZero();
        assertThat(InvoicePricing.concessionOff(12_000, List.of(new Concession(null, null)))).isZero();
    }

    @Test
    void a_percentage_concession_is_rounded_to_the_paisa() {
        assertThat(InvoicePricing.concessionOff(1_000, List.of(new Concession(null, 33.33))))
            .isEqualTo(333.3);
        assertThat(InvoicePricing.concessionOff(999.99, List.of(new Concession(null, 12.5))))
            .isEqualTo(125.0);
    }

    @Test
    void the_sibling_percentage_comes_off_the_head() {
        assertThat(InvoicePricing.siblingOff(12_345, 10)).isEqualTo(1_234.5);
        assertThat(InvoicePricing.siblingOff(12_345, 0)).isZero();
    }

    @Test
    void gst_is_rounded_per_line_so_the_lines_add_up_to_the_invoice() {
        // 18% of 1.10 is 0.198, which a parent reads as 0.20 on each line.
        // Taxing the 3.30 subtotal instead would give 0.59, and the lines
        // would not add up to the invoice.
        var invoice = InvoicePricing.invoice(List.of(
            InvoicePricing.line(1.10, 18, 0, 0),
            InvoicePricing.line(1.10, 18, 0, 0),
            InvoicePricing.line(1.10, 18, 0, 0)));

        assertThat(invoice.lines()).allSatisfy(l -> assertThat(l.gst()).isEqualTo(0.20));
        assertThat(invoice.subtotal()).isEqualTo(3.30);
        assertThat(invoice.gst()).isEqualTo(0.60);
        assertThat(invoice.total()).isEqualTo(3.90);
    }

    @Test
    void the_invoice_totals_mix_taxed_and_untaxed_heads() {
        var invoice = InvoicePricing.invoice(List.of(
            InvoicePricing.line(25_000, 0, 2_500, 0),     // tuition, exempt, a concession
            InvoicePricing.line(3_000, 18, 0, 300),       // transport, taxed, a sibling cut
            InvoicePricing.line(1_500, 18, 0, 0)));       // activity, taxed

        assertThat(invoice.subtotal()).isEqualTo(22_500 + 2_700 + 1_500);
        assertThat(invoice.gst()).isEqualTo(486 + 270);
        assertThat(invoice.total()).isEqualTo(26_700 + 756);
    }

    @Test
    void an_invoice_with_no_lines_is_zero() {
        var invoice = InvoicePricing.invoice(List.of());

        assertThat(invoice.subtotal()).isZero();
        assertThat(invoice.gst()).isZero();
        assertThat(invoice.total()).isZero();
    }
}
