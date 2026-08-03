package com.schoolsoft.transport.api;

import java.time.Instant;
import java.util.UUID;

public record GeofenceStatusDto(
    UUID vehicleId, UUID stopId, boolean insideGeofence, double distanceMeters, int geofenceRadiusM, Instant asOf
) {}
