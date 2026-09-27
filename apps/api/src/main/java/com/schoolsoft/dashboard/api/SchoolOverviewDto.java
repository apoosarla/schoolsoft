package com.schoolsoft.dashboard.api;

import java.util.Map;

/**
 * One school at a glance. {@code dashboard.view} opens it, but the fee and
 * admissions figures are school-wide money and pipeline numbers that belong
 * to whoever may read those modules' reports: they come back {@code null} for
 * a caller without {@code fee.report.view} or {@code admission.view} (BUG-26).
 */
public record SchoolOverviewDto(
    long activeEnrolments,
    long presentToday,
    Double attendanceTodayPct,
    Double feeInvoicedMtd,
    Double feeCollectedMtd,
    Double feeCollectionMtdPct,
    Map<String, Long> admissionsFunnel,
    long announcementsPublished30d,
    long announcementReads30d
) {}
