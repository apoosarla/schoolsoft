package com.schoolsoft.transport.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One driver, or one vehicle, on two routes running the same way over the
 * same days — two pickups at once, say. Routes carry a direction but no
 * times, so this is a warning, not a refusal: two pickups can run one after
 * the other. A {@code both} route runs either way and clashes with anything.
 *
 * <p>{@code kind} is {@code driver} or {@code vehicle}. The overlap runs
 * {@code from} to {@code to} ({@code null}: open-ended), counted from today.
 * {@code secondAssignmentId} is {@code null} when the second side is a
 * proposed assignment not yet saved.</p>
 */
public record RouteClashDto(
    String kind, UUID resourceId, String resourceName,
    UUID firstAssignmentId, String firstRouteCode, String firstDirection,
    UUID secondAssignmentId, String secondRouteCode, String secondDirection,
    LocalDate from, LocalDate to
) {}
