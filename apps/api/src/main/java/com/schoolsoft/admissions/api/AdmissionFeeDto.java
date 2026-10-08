package com.schoolsoft.admissions.api;

import java.util.UUID;

/**
 * What a school charges to process an application for one grade in one year
 * (ADM-13). A grade with no row charges nothing, and its applications pass
 * through {@code fee_pending} with nothing to pay.
 */
public record AdmissionFeeDto(
    UUID schoolId,
    UUID academicYearId,
    UUID gradeId,
    double amount
) {}
