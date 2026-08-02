package com.schoolsoft.fees.api;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntryDto(
    UUID id,
    UUID journalId,
    String accountCode,
    double debit,
    double credit,
    String narration,
    String sourceType,
    UUID sourceId,
    Instant postedAt
) {}
