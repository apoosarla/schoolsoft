package com.schoolsoft.fees.api;

import java.util.UUID;

/**
 * Where an application's admission fee stands: the invoice the office records
 * the payment against, and whether it is settled.
 *
 * <p>{@code status} is the invoice's own. {@code cancelled} is a bill nobody
 * paid on an application that then closed; {@code refunded} is one whose money
 * went back.</p>
 */
public record AdmissionFeeStatusDto(
    UUID invoiceId,
    String invoiceNo,
    double total,
    double paid,
    String status,
    /** What has been paid back out, so a closed application shows where its money went. */
    double refunded
) {
    public double outstanding() {
        return Math.max(0, Math.round((total - paid) * 100.0) / 100.0);
    }

    /**
     * Still owed by a family the school is still dealing with. A cancelled or
     * refunded bill has a balance on paper and nothing to collect.
     */
    public boolean owing() {
        return outstanding() > 0 && !"cancelled".equals(status) && !"refunded".equals(status);
    }
}
