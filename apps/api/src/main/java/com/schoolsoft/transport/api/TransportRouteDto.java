package com.schoolsoft.transport.api;

import java.util.UUID;

public record TransportRouteDto(UUID id, UUID schoolId, String code, String name, String direction, boolean isActive) {}
