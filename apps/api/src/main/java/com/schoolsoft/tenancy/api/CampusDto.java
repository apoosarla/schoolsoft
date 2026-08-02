package com.schoolsoft.tenancy.api;

import java.util.UUID;

public record CampusDto(UUID id, UUID schoolId, String name, boolean isPrimary) {}
