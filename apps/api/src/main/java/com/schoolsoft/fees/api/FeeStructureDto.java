package com.schoolsoft.fees.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a grade is billed in an academic year, head by head, plus the cadence
 * the instalments follow. Next year's structure is a clone, never an edit —
 * last year's invoices have to keep meaning what they said.
 *
 * <p>{@code version} guards the line editor. Replacing the lines deletes every
 * one of them and re-inserts, so an overlapping save does not merge or
 * partially lose — it drops a whole fee schedule. The client sends back the
 * version it read and a stale save is refused.</p>
 */
public record FeeStructureDto(
    UUID id,
    UUID schoolId,
    UUID gradeId,
    UUID academicYearId,
    String name,
    Map<String, Object> schedule,
    List<Line> lines,
    double total,
    long version
) {
    public record Line(UUID id, UUID feeHeadId, String feeHeadCode, String feeHeadName,
                       double amount, double gstRatePct) {}
}
