package com.schoolsoft.transport.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.transport.internal.TransportRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/transport")
public class TransportController {

    private final TransportRepository repo;
    public TransportController(TransportRepository repo) { this.repo = repo; }

    // -------------------------- Vehicles --------------------------

    @PreAuthorize("@perm.can('transport.view')")
    @GetMapping("/vehicles")
    public List<VehicleDto> vehicles(@RequestParam UUID schoolId) {
        return repo.listVehicles(schoolId);
    }

    public record CreateVehicleRequest(@NotBlank String registrationNo, String model, Integer capacity) {}

    @PreAuthorize("@perm.can('transport.manage')")
    @PostMapping("/vehicles")
    public VehicleDto createVehicle(@RequestParam UUID schoolId, @RequestBody CreateVehicleRequest req) {
        return repo.createVehicle(schoolId, req.registrationNo(), req.model(), req.capacity());
    }

    // -------------------------- Drivers --------------------------

    @PreAuthorize("@perm.can('transport.view')")
    @GetMapping("/drivers")
    public List<DriverDto> drivers(@RequestParam UUID schoolId, @RequestParam(required = false) UUID staffId) {
        return repo.listDrivers(schoolId, staffId);
    }

    public record CreateDriverRequest(@NotBlank String name, String phone, String licenseNo, UUID staffId) {}

    @PreAuthorize("@perm.can('transport.manage')")
    @PostMapping("/drivers")
    public DriverDto createDriver(@RequestParam UUID schoolId, @RequestBody CreateDriverRequest req) {
        return repo.createDriver(schoolId, req.staffId(), req.name(), req.phone(), req.licenseNo());
    }

    // -------------------------- Routes + Stops --------------------------

    @PreAuthorize("@perm.can('transport.view')")
    @GetMapping("/routes")
    public List<TransportRouteDto> routes(@RequestParam UUID schoolId) {
        return repo.listRoutes(schoolId);
    }

    public record CreateRouteRequest(@NotBlank String code, @NotBlank String name, String direction) {}

    @PreAuthorize("@perm.can('transport.manage')")
    @PostMapping("/routes")
    public TransportRouteDto createRoute(@RequestParam UUID schoolId, @RequestBody CreateRouteRequest req) {
        return repo.createRoute(schoolId, req.code(), req.name(), req.direction());
    }

    @PreAuthorize("@perm.can('transport.view')")
    @GetMapping("/routes/{routeId}/stops")
    public List<TransportStopDto> stops(@PathVariable UUID routeId) {
        return repo.listStops(routeId);
    }

    public record AddStopRequest(@NotBlank String name, int sortOrder, Double lat, Double lng, Double fee) {}

    @PreAuthorize("@perm.can('transport.manage')")
    @PostMapping("/routes/{routeId}/stops")
    public TransportStopDto addStop(@PathVariable UUID routeId, @RequestBody AddStopRequest req) {
        return repo.addStop(routeId, req.name(), req.sortOrder(), req.lat(), req.lng(), req.fee());
    }

    // -------------------------- Student assignment --------------------------

    public record AssignStudentRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, @NotNull UUID routeId, @NotNull UUID stopId, @NotNull LocalDate startsOn
    ) {}

    @PreAuthorize("@perm.can('transport.manage')")
    @PostMapping("/student-assignments")
    public StudentTransportDto assignStudent(@RequestBody AssignStudentRequest req) {
        return repo.assignStudent(req.schoolId(), req.studentId(), req.routeId(), req.stopId(), req.startsOn());
    }

    @PreAuthorize("@perm.canAny('transport.view', 'transport.drive')")
    @GetMapping("/routes/{routeId}/students")
    public List<StudentTransportDto> studentsOnRoute(
        @PathVariable UUID routeId,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate onDate
    ) {
        return repo.listStudentsOnRoute(routeId, onDate);
    }

    public record ChangeAssignmentRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, @NotNull UUID routeId, @NotNull UUID stopId,
        @NotNull LocalDate effectiveFrom
    ) {}

    /** Moves a rider to another stop or route from a date (TRN-06). */
    @PreAuthorize("@perm.can('transport.manage')")
    @PostMapping("/student-assignments/change")
    public StudentTransportDto changeAssignment(@RequestBody ChangeAssignmentRequest req) {
        return repo.changeAssignment(req.schoolId(), req.studentId(), req.routeId(), req.stopId(),
            req.effectiveFrom());
    }

    public record EndAssignmentRequest(@NotNull UUID studentId, @NotNull LocalDate lastDay) {}

    @PreAuthorize("@perm.can('transport.manage')")
    @PostMapping("/student-assignments/end")
    public org.springframework.http.ResponseEntity<Void> endAssignment(@RequestBody EndAssignmentRequest req) {
        repo.endAssignment(req.studentId(), req.lastDay());
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    // -------------------------- GPS + Trips --------------------------

    public record GpsPingRequest(
        @NotNull UUID vehicleId, @NotNull Instant occurredAt, double lat, double lng, Double speedKmh, Double heading
    ) {}

    @PreAuthorize("@perm.can('transport.drive')")
    @PostMapping("/gps-pings")
    public ResponseEntity<Void> recordPing(@RequestBody GpsPingRequest req) {
        repo.recordGpsPing(req.vehicleId(), req.occurredAt(), req.lat(), req.lng(), req.speedKmh(), req.heading());
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@perm.canAny('transport.view', 'transport.track')")
    @GetMapping("/vehicles/{vehicleId}/gps-pings")
    public List<GpsPingDto> recentPings(@PathVariable UUID vehicleId, @RequestParam(defaultValue = "50") int limit) {
        return repo.recentPings(vehicleId, Math.min(limit, 500));
    }

    public record StartTripRequest(@NotNull UUID schoolId, @NotNull UUID routeId, @NotNull UUID vehicleId, @NotNull UUID driverId, @NotBlank String direction) {}

    @PreAuthorize("@perm.can('transport.drive')")
    @PostMapping("/trips/start")
    public TripDto startTrip(@RequestBody StartTripRequest req) {
        return repo.startTrip(req.schoolId(), req.routeId(), req.vehicleId(), req.driverId(), req.direction());
    }

    @PreAuthorize("@perm.can('transport.drive')")
    @PostMapping("/trips/{id}/end")
    public TripDto endTrip(@PathVariable UUID id) {
        return repo.endTrip(id);
    }

    @PreAuthorize("@perm.canAny('transport.view', 'transport.track')")
    @GetMapping("/trips")
    public List<TripDto> trips(
        @RequestParam(required = false) UUID driverId, @RequestParam(required = false) UUID schoolId,
        @RequestParam(defaultValue = "20") int limit
    ) {
        int cappedLimit = Math.min(limit, 200);
        if (driverId != null) return repo.tripsForDriver(driverId, cappedLimit);
        if (schoolId != null) return repo.tripsForSchool(schoolId, cappedLimit);
        throw new IllegalArgumentException("Either driverId or schoolId is required");
    }

    @PreAuthorize("@perm.canAny('transport.view', 'transport.track')")
    @GetMapping("/trips/{id}")
    public TripDto trip(@PathVariable UUID id) {
        return repo.findTrip(id);
    }

    public record CheckInRequest(@NotNull UUID studentId, @NotBlank String status) {}

    @PreAuthorize("@perm.can('transport.drive')")
    @PostMapping("/trips/{id}/checkin")
    public TripDto checkIn(@PathVariable UUID id, @RequestBody CheckInRequest req) {
        return repo.checkIn(id, req.studentId(), req.status());
    }

    // -------------------------- Geofencing --------------------------

    @PreAuthorize("@perm.canAny('transport.view', 'transport.track')")
    @GetMapping("/geofence-status")
    public GeofenceStatusDto geofenceStatus(@RequestParam UUID vehicleId, @RequestParam UUID stopId) {
        return repo.checkGeofence(vehicleId, stopId);
    }
}
