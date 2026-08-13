package com.schoolsoft.rollover.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Where one child is going, and why.
 *
 * A row with no {@code toSectionId} is not a failure of the run — it is a
 * question for the school: the grade above is full, or nobody decided whether
 * this child passes. Commit leaves those alone and refuses to close the year
 * while any remain, because a child in a closed year with no next enrolment is
 * a child who has quietly vanished from the school.
 */
public record RolloverAllocationDto(
    UUID id,
    UUID rolloverRunId,
    UUID studentId,
    String studentName,
    String admissionNo,
    UUID fromEnrolmentId,
    UUID fromSectionId,
    String fromSectionLabel,
    /** promote | detain | graduate */
    String decision,
    UUID toSectionId,
    String toSectionLabel,
    String rollNo,
    String overCapacityReason,
    /** planned | applied | skipped */
    String state,
    String note,
    UUID newEnrolmentId,
    int batchNo,
    Instant appliedAt
) {}
