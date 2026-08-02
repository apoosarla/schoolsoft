package com.schoolsoft.fees.api;

import java.time.Instant;
import java.util.UUID;

public record PaymentDto(
    UUID id,
    UUID schoolId,
    UUID feeInvoiceId,
    double amount,
    String gateway,
    String method,
    String status,
    String idempotencyKey,
    Instant capturedAt
) {}
