package com.schoolsoft.tenancy.api;

import java.util.UUID;

public record ChainStatsDto(
    UUID chainId, long schoolCount, long activeEnrolments, long staffCount, double feeCollectedTotal
) {}
