package com.schoolsoft.tenancy.api;

import java.util.UUID;

public record SubjectDto(UUID id, UUID schoolId, String code, String name, String boardCode) {}
