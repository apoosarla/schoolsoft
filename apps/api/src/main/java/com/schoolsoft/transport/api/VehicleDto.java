package com.schoolsoft.transport.api;

import java.util.UUID;

public record VehicleDto(UUID id, UUID schoolId, String registrationNo, String model, Integer capacity, boolean isActive) {}
