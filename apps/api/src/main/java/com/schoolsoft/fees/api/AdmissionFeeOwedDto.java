package com.schoolsoft.fees.api;

import java.util.UUID;

/** One application whose admission fee is unpaid — the fee module's half of "who owes". */
public record AdmissionFeeOwedDto(UUID applicationId, UUID invoiceId, String invoiceNo, double total, double paid) {
    public double outstanding() {
        return Math.round((total - paid) * 100.0) / 100.0;
    }
}
