package com.schoolsoft.tenancy.api;

import java.time.LocalDate;
import java.util.UUID;

public record TermDto(UUID id, UUID academicYearId, String code, String name, LocalDate startsOn, LocalDate endsOn) {}
