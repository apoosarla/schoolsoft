package com.schoolsoft.transport.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * An active route with a day, today or later, on which nobody is assigned to
 * drive it. {@code uncoveredFrom} is the first such day; {@code coveredAgainOn}
 * is when an assignment picks it up again, or {@code null} if none does.
 */
public record RouteGapDto(UUID routeId, String routeCode, String routeName,
                          LocalDate uncoveredFrom, LocalDate coveredAgainOn) {}
