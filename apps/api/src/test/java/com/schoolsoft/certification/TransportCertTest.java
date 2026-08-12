package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-TRN — transport. */
class TransportCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_TRN_01_routeStopsAndAssignmentsProduceAMatchingRoster() {
        String token = principalToken(cbse());
        var route = post("/v1/transport/routes?schoolId=" + cbse().id(),
            Map.of("code", "R-CERT-" + UUID.randomUUID().toString().substring(0, 5),
                "name", "Certification route", "direction", "pickup"), token);
        assertThat(route.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID routeId = UUID.fromString(route.getBody().get("id").asText());

        var stop = post("/v1/transport/routes/" + routeId + "/stops",
            Map.of("name", "Banjara Hills", "sortOrder", 1, "lat", 17.41, "lng", 78.44, "fee", 6000.0), token);
        assertThat(stop.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID stopId = UUID.fromString(stop.getBody().get("id").asText());

        UUID studentId = studentsIn(currentFocusSection(cbse())).get(5);
        var assigned = post("/v1/transport/student-assignments", Map.of(
            "schoolId", cbse().id(), "studentId", studentId, "routeId", routeId,
            "stopId", stopId, "startsOn", "2026-08-01"), token);
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);

        var roster = get("/v1/transport/routes/" + routeId + "/students", token).getBody();
        assertThat(roster).hasSize(1);
        assertThat(roster.get(0).get("studentId").asText()).isEqualTo(studentId.toString());
        assertThat(get("/v1/transport/routes/" + routeId + "/stops", token).getBody()).hasSize(1);
    }

    @Test @Tag("P2")
    @Disabled("GAP-30 — route capacity is never checked against the assigned vehicle's capacity "
        + "(Phase 8).")
    void cert_TRN_02_routeCapacityIsEnforcedAgainstVehicleCapacity() {
    }

    @Test @Tag("P1")
    @Disabled("A trip can be started, checked into and ended, but boarding and alighting raise no parent "
        + "notification: nothing calls NotificationService from the transport path. New gap found in "
        + "Phase 0.")
    void cert_TRN_03_tripCheckInsNotifyParentsOnBoardingAndAlighting() {
    }

    @Test @Tag("P1")
    void cert_TRN_04_geofenceEntryIsReportedFromLiveGpsPings() {
        String token = principalToken(cbse());
        UUID vehicleId = queryOne("SELECT id FROM vehicle WHERE school_id = ? LIMIT 1", UUID.class, cbse().id());
        UUID stopId = queryOne(
            "SELECT s.id FROM transport_stop s JOIN transport_route r ON r.id = s.route_id "
            + "WHERE r.school_id = ? ORDER BY s.sort_order LIMIT 1", UUID.class, cbse().id());
        double stopLat = queryOne("SELECT lat FROM transport_stop WHERE id = ?", Double.class, stopId);
        double stopLng = queryOne("SELECT lng FROM transport_stop WHERE id = ?", Double.class, stopId);

        // Far away first: outside the geofence.
        post("/v1/transport/gps-pings", body("vehicleId", vehicleId, "occurredAt", Instant.now().toString(),
            "lat", stopLat + 0.5, "lng", stopLng + 0.5, "speedKmh", 30.0, "heading", 90.0), token);
        var outside = get("/v1/transport/geofence-status?vehicleId=" + vehicleId + "&stopId=" + stopId, token);
        assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outside.getBody().get("insideGeofence").asBoolean()).isFalse();

        // Then at the stop: inside.
        post("/v1/transport/gps-pings", body("vehicleId", vehicleId, "occurredAt", Instant.now().toString(),
            "lat", stopLat, "lng", stopLng, "speedKmh", 0.0, "heading", 90.0), token);
        var inside = get("/v1/transport/geofence-status?vehicleId=" + vehicleId + "&stopId=" + stopId, token);
        assertThat(inside.getBody().get("insideGeofence").asBoolean()).isTrue();
        assertThat(inside.getBody().get("distanceMeters").asDouble())
            .isLessThan(inside.getBody().get("geofenceRadiusM").asDouble());
    }

    @Test @Tag("P2")
    @Disabled("GAP-30 — nothing compares class attendance with bus boarding, so no mismatch alert exists "
        + "(Phase 8).")
    void cert_TRN_05_boardedVersusAttendanceMismatchAlertsTheOffice() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-30 + GAP-09 — student_transport carries starts_on/ends_on but nothing effective-dates a "
        + "change through the roster or the fee schedule (Phases 4 and 8).")
    void cert_TRN_06_midYearStopChangeAdjustsRosterAndFees() {
    }

    @Test @Tag("P2")
    @Disabled("Check-in writes into the trip manifest with no client-supplied event id, so a queued "
        + "offline check-in cannot be de-duplicated on replay. New gap found in Phase 0.")
    void cert_TRN_07_offlineDriverCheckInsSyncWithoutDuplicates() {
    }

    @Test @Tag("P3")
    @Disabled("No trip reassignment path for a breakdown or driver substitution mid-route, and no parent "
        + "notification. New gap found in Phase 0.")
    void cert_TRN_08_breakdownReassignsTheTripAndInformsParents() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-03 — withdrawal has no workflow, so nothing removes a withdrawn student from the route "
        + "roster (Phase 7).")
    void cert_TRN_09_withdrawnStudentLeavesTheRouteRoster() {
    }
}
