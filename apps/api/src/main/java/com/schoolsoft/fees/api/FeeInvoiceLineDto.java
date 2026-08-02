package com.schoolsoft.fees.api;

import java.util.UUID;

public record FeeInvoiceLineDto(
    UUID id, UUID feeInvoiceId, UUID feeHeadId, String description, double amount, double discount, double gst
) {}
