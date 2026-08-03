package com.schoolsoft.transport.api;

import java.time.Instant;
import java.util.UUID;

public record TripDto(
    UUID id, UUID schoolId, UUID routeId, UUID vehicleId, UUID driverId,
    String direction, Instant startedAt, Instant endedAt
) {}
