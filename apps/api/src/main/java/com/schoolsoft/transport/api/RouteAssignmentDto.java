package com.schoolsoft.transport.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Who drives a route, in which vehicle, over which days. {@code effectiveTo}
 * is the last day it counts, or {@code null} while it runs on. The names ride
 * along so the office's table needs no second read.
 */
public record RouteAssignmentDto(
    UUID id,
    UUID routeId, String routeCode, String routeName,
    UUID vehicleId, String registrationNo,
    UUID driverId, String driverName,
    LocalDate effectiveFrom, LocalDate effectiveTo
) {}
