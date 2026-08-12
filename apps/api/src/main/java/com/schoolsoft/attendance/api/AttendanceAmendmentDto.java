package com.schoolsoft.attendance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A request to change a register that has already been signed off (ATT-06).
 *
 * The prior value lives here rather than being overwritten, so the question
 * "what did the teacher actually mark that morning?" still has an answer after
 * the correction is applied.
 */
public record AttendanceAmendmentDto(
    UUID id,
    UUID schoolId,
    UUID attendanceRecordId,
    UUID studentId,
    UUID sectionId,
    LocalDate onDate,
    Integer periodNo,
    String oldStatus,
    String newStatus,
    String reason,
    UUID requestedByUserId,
    Instant requestedAt,
    String status,
    UUID decidedByUserId,
    Instant decidedAt,
    String decisionNote
) {}
