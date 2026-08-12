package com.schoolsoft.enrolment.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One subject a student studies, and where it came from: {@code compulsory}
 * for a subject the section takes as a whole, {@code elective} for one the
 * student elected out of an option block.
 */
public record StudentSubjectDto(
    UUID id,
    UUID enrolmentId,
    UUID studentId,
    UUID subjectId,
    String subjectCode,
    String subjectName,
    String origin,
    UUID electiveGroupId,
    String electiveGroupCode,
    String status,
    LocalDate effectiveFrom,
    LocalDate effectiveTo
) {}
