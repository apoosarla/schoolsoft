package com.schoolsoft.tenancy.api;

import java.util.UUID;

public record GradeDto(UUID id, String code, String name, int sortOrder) {}
