package com.schoolsoft.transport.api;

import java.util.UUID;

public record TransportStopDto(
    UUID id, UUID routeId, String name, int sortOrder, Double lat, Double lng, Double fee
) {}
