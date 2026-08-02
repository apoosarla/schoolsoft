package com.schoolsoft.admissions.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AdmissionApplicationDto(
    UUID id,
    UUID schoolId,
    UUID academicYearId,
    UUID gradeId,
    String applicationNo,
    String applicantFirstName,
    String applicantLastName,
    LocalDate applicantDob,
    String applicantGender,
    String guardianName,
    String guardianPhone,
    String guardianEmail,
    String source,
    String state,
    Double testScore,
    String interviewNotes,
    LocalDate offerExpiresOn,
    UUID convertedStudentId,
    Instant createdAt
) {}
