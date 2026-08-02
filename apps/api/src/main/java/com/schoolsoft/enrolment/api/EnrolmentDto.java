package com.schoolsoft.enrolment.api;

import java.time.LocalDate;
import java.util.UUID;

public record EnrolmentDto(
    UUID id,
    UUID schoolId,
    UUID studentId,
    UUID sectionId,
    String sectionLabel,
    UUID academicYearId,
    LocalDate startsOn,
    LocalDate endsOn,
    String status,
    String rollNo
) {}
