package com.schoolsoft.fees.api;

import java.util.UUID;

/**
 * Where an application's admission fee stands: the invoice the office records
 * the payment against, and whether it is settled.
 */
public record AdmissionFeeStatusDto(
    UUID invoiceId,
    String invoiceNo,
    double total,
    double paid,
    String status
) {
    public double outstanding() {
        return Math.max(0, Math.round((total - paid) * 100.0) / 100.0);
    }

    /** Paid in full — the only thing that lets the application leave {@code fee_pending} forwards. */
    public boolean settled() {
        return outstanding() <= 0;
    }
}
