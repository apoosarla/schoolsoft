package com.schoolsoft.fees.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One billing run: a cycle, the cohort it covered, and what it produced. The
 * row is the idempotency key — re-running October finds this record and writes
 * nothing — so listing the runs is also the answer to "has October been billed
 * yet, and by whom".
 */
public record FeeScheduleRunDto(
    UUID id,
    UUID schoolId,
    UUID academicYearId,
    String cycleLabel,
    /** Null for a whole-school run. */
    UUID gradeId,
    String gradeCode,
    LocalDate dueOn,
    String state,
    int invoicesCreated,
    int studentsSkipped,
    double totalBilled,
    UUID runByStaffId,
    Instant createdAt
) {}
