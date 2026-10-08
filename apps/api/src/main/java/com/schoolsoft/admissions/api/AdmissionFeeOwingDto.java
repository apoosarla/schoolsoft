package com.schoolsoft.admissions.api;

import java.util.UUID;

/**
 * An application that has moved past the fee stage and owes its admission fee
 * — in practice a cheque that came back after the office had moved the file
 * on. The board shows these so admissions hears about it from its own screen
 * rather than from accounts.
 */
public record AdmissionFeeOwingDto(
    UUID applicationId,
    String applicationNo,
    String applicantName,
    String state,
    UUID invoiceId,
    String invoiceNo,
    double outstanding
) {}
