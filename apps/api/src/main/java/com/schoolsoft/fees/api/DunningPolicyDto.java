package com.schoolsoft.fees.api;

import java.util.List;
import java.util.UUID;

/**
 * How hard, and how soon, a school chases an unpaid invoice: the grace before
 * it turns overdue, the days after the due date a reminder goes out, and what
 * a late fee costs.
 *
 * Readable as well as writable because dunning writes to families. An operator
 * editing a cadence they cannot see is how a household ends up with three
 * reminders in one week.
 */
public record DunningPolicyDto(
    UUID id,
    UUID schoolId,
    int graceDays,
    List<Integer> reminderDays,
    Double lateFeePct,
    Double lateFeeFlat,
    UUID lateFeeHeadId,
    boolean isActive
) {}
