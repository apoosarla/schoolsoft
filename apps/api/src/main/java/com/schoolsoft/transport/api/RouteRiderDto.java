package com.schoolsoft.transport.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A rider on a route, as the driver's check-in screen needs them: the
 * assignment, and enough of the student to call a name down the bus.
 *
 * <p>This is the route-scoped student read the driver role was missing. Before
 * it, the roster returned {@code studentId} and nothing else, so driver-app
 * read every rider back out of {@code /v1/people/students/&#123;id&#125;} —
 * which is why {@code driver} was granted school-wide {@code student.view}.
 * The three fields here are what that screen actually displayed; a driver has
 * no business with a date of birth, a guardian's number or an address, so none
 * are on it.</p>
 */
public record RouteRiderDto(
    UUID id, UUID studentId, UUID routeId, UUID stopId, LocalDate startsOn, LocalDate endsOn,
    String admissionNo, String firstName, String lastName, String sectionLabel
) {}
