package com.schoolsoft.rollover.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One attempt at moving a school into the next year.
 *
 * {@code state} is the whole contract: {@code draft} → {@code structure_cloned}
 * → {@code allocated} → {@code committed}, with {@code rolled_back} reachable
 * from anywhere before the target year is activated. Nothing irreversible
 * happens until commit, and even that is undoable while the new year is still
 * only planned.
 */
public record RolloverRunDto(
    UUID id,
    UUID schoolId,
    UUID fromAcademicYearId,
    String fromAcademicYearCode,
    UUID toAcademicYearId,
    String toAcademicYearCode,
    String toAcademicYearStatus,
    boolean toAcademicYearIsCurrent,
    String runKey,
    String state,
    int batchSize,
    int batchesDone,
    Map<String, Object> stats,
    UUID startedByStaffId,
    Instant createdAt,
    Instant committedAt,
    Instant rolledBackAt
) {}
