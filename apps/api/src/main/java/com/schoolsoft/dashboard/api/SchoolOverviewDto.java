package com.schoolsoft.dashboard.api;

import java.util.Map;

public record SchoolOverviewDto(
    long activeEnrolments,
    long presentToday,
    Double attendanceTodayPct,
    double feeInvoicedMtd,
    double feeCollectedMtd,
    Double feeCollectionMtdPct,
    Map<String, Long> admissionsFunnel,
    long announcementsPublished30d,
    long announcementReads30d
) {}
