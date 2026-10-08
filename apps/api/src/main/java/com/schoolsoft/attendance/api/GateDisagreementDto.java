package com.schoolsoft.attendance.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A day on which a gate device reported a student and the register says they
 * were not there (ATT-08). One of the two is wrong: a card lent to a friend, a
 * register filled in from memory, or a child who came through the gate and
 * never reached the classroom — which is the one that matters.
 */
public record GateDisagreementDto(
    UUID recordId,
    LocalDate onDate,
    UUID studentId,
    String studentName,
    String admissionNo,
    UUID sectionId,
    String sectionLabel,
    String status,
    String source,
    String gateSource,
    Instant gateSeenAt
) {}
