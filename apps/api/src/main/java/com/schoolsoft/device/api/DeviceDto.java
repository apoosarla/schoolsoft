package com.schoolsoft.device.api;

import java.time.Instant;
import java.util.UUID;

public record DeviceDto(
    UUID id,
    UUID schoolId,
    UUID campusId,
    String kind,
    String vendor,
    String model,
    String serialNo,
    String location,
    UUID assignedVehicleId,
    Instant lastSeenAt,
    boolean isActive
) {}
