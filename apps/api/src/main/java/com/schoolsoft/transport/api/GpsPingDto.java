package com.schoolsoft.transport.api;

import java.time.Instant;
import java.util.UUID;

public record GpsPingDto(UUID vehicleId, Instant occurredAt, double lat, double lng, Double speedKmh, Double heading) {}
