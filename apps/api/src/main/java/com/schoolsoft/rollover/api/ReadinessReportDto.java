package com.schoolsoft.rollover.api;

import java.util.List;
import java.util.UUID;

/**
 * What still stands between a school and closing its year (YEC-01).
 *
 * Deliberately assembled from the modules that own each answer rather than
 * from a rollover-specific table: an unpublished assessment is assessment's
 * fact, an unmarked day is the calendar's, an arrear is the fee ledger's. The
 * counts are the summary a principal reads; {@code items} is the list the
 * office has to work through, capped so that a school with a thousand unmarked
 * days gets a report rather than a download.
 */
public record ReadinessReportDto(
    UUID schoolId,
    UUID academicYearId,
    String academicYearCode,
    /** True when every count below is zero: nothing is left to chase. */
    boolean ready,
    int activeEnrolments,
    int unpublishedAssessments,
    int unlockedReportCards,
    int missingPromotionDecisions,
    int unmarkedAttendanceDays,
    int studentsWithDues,
    double outstandingTotal,
    List<Item> items
) {
    /**
     * One thing to fix. {@code kind} groups them; {@code detail} is what the
     * office reads; {@code targetId} is the row to open.
     */
    public record Item(String kind, String detail, UUID targetId) {}
}
