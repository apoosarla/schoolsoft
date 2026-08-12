package com.schoolsoft.fees.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a grade is billed in an academic year, head by head, plus the cadence
 * the instalments follow. Next year's structure is a clone, never an edit —
 * last year's invoices have to keep meaning what they said.
 */
public record FeeStructureDto(
    UUID id,
    UUID schoolId,
    UUID gradeId,
    UUID academicYearId,
    String name,
    Map<String, Object> schedule,
    List<Line> lines,
    double total
) {
    public record Line(UUID id, UUID feeHeadId, String feeHeadCode, String feeHeadName,
                       double amount, double gstRatePct) {}
}
