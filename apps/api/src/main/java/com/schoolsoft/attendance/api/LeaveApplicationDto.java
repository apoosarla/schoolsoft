package com.schoolsoft.attendance.api;

import java.time.LocalDate;
import java.util.UUID;

public record LeaveApplicationDto(
    UUID id,
    UUID schoolId,
    String subjectType,
    UUID subjectId,
    LocalDate fromDate,
    LocalDate toDate,
    String reason,
    String status,
    UUID approverStaffId
) {}
