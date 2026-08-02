package com.schoolsoft.assessment.api;

import java.time.Instant;
import java.util.UUID;

public record ReportCardDto(
    UUID id,
    UUID schoolId,
    UUID studentId,
    UUID academicYearId,
    UUID termId,
    String strategyCode,
    String templateCode,
    boolean isLocked,
    Instant generatedAt
) {}
