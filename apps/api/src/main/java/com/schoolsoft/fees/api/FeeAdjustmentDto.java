package com.schoolsoft.fees.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A change to what is owed or what was paid, after the invoice was issued: a
 * bounced cheque (reversal), a refund, a credit note, a waived late fee, or a
 * charge such as a library fine. Every one of them posts to the ledger, so the
 * accountant's view and the parent's view move together.
 */
public record FeeAdjustmentDto(
    UUID id,
    UUID schoolId,
    UUID feeInvoiceId,
    UUID paymentId,
    String kind,
    double amount,
    String reason,
    UUID approvedByStaffId,
    Instant createdAt
) {}
