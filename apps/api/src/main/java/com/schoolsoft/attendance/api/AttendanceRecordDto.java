package com.schoolsoft.attendance.api;

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
    String notes
) {}
