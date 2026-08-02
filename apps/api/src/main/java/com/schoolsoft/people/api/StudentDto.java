package com.schoolsoft.people.api;

import java.time.LocalDate;
import java.util.UUID;

public record StudentDto(
    UUID id,
    UUID schoolId,
    String admissionNo,
    String firstName,
    String middleName,
    String lastName,
    LocalDate dob,
    String gender,
    String status,
    UUID currentSectionId,
    String currentSectionLabel,
    String rollNo
) {}
