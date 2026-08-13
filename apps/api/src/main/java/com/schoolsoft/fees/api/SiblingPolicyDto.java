package com.schoolsoft.fees.api;

import java.util.UUID;

/**
 * The nth child of a family pays a percentage less. Ordering is by admission
 * date, so a younger sibling joining never re-prices the eldest.
 */
public record SiblingPolicyDto(
    UUID id,
    UUID schoolId,
    UUID academicYearId,
    int nthChild,
    double pct,
    UUID appliesToHeadId
) {}
