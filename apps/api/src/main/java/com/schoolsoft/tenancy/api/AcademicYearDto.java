package com.schoolsoft.tenancy.api;

import java.time.LocalDate;
import java.util.UUID;

public record AcademicYearDto(UUID id, String code, LocalDate startsOn, LocalDate endsOn, boolean isCurrent) {}
