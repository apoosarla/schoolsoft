package com.schoolsoft.attendance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AttendanceRecordDto(
    UUID id,
    UUID schoolId,
    UUID studentId,
    UUID sectionId,
    LocalDate onDate,
    Integer periodNo,
    String status,
    String source,
    String notes,
    /**
     * When a gate device first reported this student for this day, whoever
     * marked the record. Set beside a {@code status} of absent or leave it is
     * a disagreement between the gate and the register (ATT-08).
     */
    Instant gateSeenAt,
    String gateSource,
    /**
     * When this record was last decided. A client sends it back with a later
     * mark to say "this is what I was looking at" — the token offline sync
     * compares (ATT-09). A gate read does not move it.
     */
    Instant markedAt
) {}
