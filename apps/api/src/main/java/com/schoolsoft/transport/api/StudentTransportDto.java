package com.schoolsoft.transport.api;

import java.time.LocalDate;
import java.util.UUID;

public record StudentTransportDto(
    UUID id, UUID studentId, UUID routeId, UUID stopId, LocalDate startsOn, LocalDate endsOn
) {}
