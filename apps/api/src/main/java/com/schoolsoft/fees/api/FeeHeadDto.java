package com.schoolsoft.fees.api;

import java.util.UUID;

public record FeeHeadDto(
    UUID id, UUID schoolId, String code, String name, boolean isRecurring, double gstRatePct, String hsnSac
) {}
