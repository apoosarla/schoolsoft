package com.schoolsoft.transport.api;

import java.util.UUID;

public record DriverDto(UUID id, UUID schoolId, String name, String phone, String licenseNo, boolean isActive) {}
