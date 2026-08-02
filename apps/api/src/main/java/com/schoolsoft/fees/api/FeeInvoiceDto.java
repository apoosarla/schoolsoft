package com.schoolsoft.fees.api;

import java.time.LocalDate;
import java.util.UUID;

public record FeeInvoiceDto(
    UUID id,
    UUID schoolId,
    UUID studentId,
    String invoiceNo,
    String cycleLabel,
    LocalDate issuedOn,
    LocalDate dueOn,
    double subtotal,
    double gst,
    double total,
    double paid,
    String status
) {}
