package com.schoolsoft.people.api;

import java.time.LocalDate;
import java.util.UUID;

public record StaffDto(
    UUID id,
    UUID schoolId,
    String employeeNo,
    String firstName,
    String lastName,
    String email,
    String phone,
    String employmentType,
    LocalDate joinedOn,
    boolean isActive
) {}
