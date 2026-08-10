package com.schoolsoft.transport.api;

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

    @GetMapping("/vehicles")
    public List<VehicleDto> vehicles(@RequestParam UUID schoolId) {
        return repo.listVehicles(schoolId);
    }

    public record CreateVehicleRequest(@NotBlank String registrationNo, String model, Integer capacity) {}

    @PostMapping("/vehicles")
    public VehicleDto createVehicle(@RequestParam UUID schoolId, @RequestBody CreateVehicleRequest req) {
        return repo.createVehicle(schoolId, req.registrationNo(), req.model(), req.capacity());
    }

    // -------------------------- Drivers --------------------------

    @GetMapping("/drivers")
    public List<DriverDto> drivers(@RequestParam UUID schoolId, @RequestParam(required = false) UUID staffId) {
        return repo.listDrivers(schoolId, staffId);
    }

    public record CreateDriverRequest(@NotBlank String name, String phone, String licenseNo) {}

    @PostMapping("/drivers")
    public DriverDto createDriver(@RequestParam UUID schoolId, @RequestBody CreateDriverRequest req) {
        return repo.createDriver(schoolId, req.name(), req.phone(), req.licenseNo());
    }

    // -------------------------- Routes + Stops --------------------------

    @GetMapping("/routes")
    public List<TransportRouteDto> routes(@RequestParam UUID schoolId) {
        return repo.listRoutes(schoolId);
    }

    public record CreateRouteRequest(@NotBlank String code, @NotBlank String name, String direction) {}

    @PostMapping("/routes")
    public TransportRouteDto createRoute(@RequestParam UUID schoolId, @RequestBody CreateRouteRequest req) {
        return repo.createRoute(schoolId, req.code(), req.name(), req.direction());
    }

    @GetMapping("/routes/{routeId}/stops")
    public List<TransportStopDto> stops(@PathVariable UUID routeId) {
        return repo.listStops(routeId);
    }

    public record AddStopRequest(@NotBlank String name, int sortOrder, Double lat, Double lng, Double fee) {}

    @PostMapping("/routes/{routeId}/stops")
    public TransportStopDto addStop(@PathVariable UUID routeId, @RequestBody AddStopRequest req) {
        return repo.addStop(routeId, req.name(), req.sortOrder(), req.lat(), req.lng(), req.fee());
    }

    // -------------------------- Student assignment --------------------------

    public record AssignStudentRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, @NotNull UUID routeId, @NotNull UUID stopId, @NotNull LocalDate startsOn
    ) {}

    @PostMapping("/student-assignments")
    public StudentTransportDto assignStudent(@RequestBody AssignStudentRequest req) {
        return repo.assignStudent(req.schoolId(), req.studentId(), req.routeId(), req.stopId(), req.startsOn());
    }

    @GetMapping("/routes/{routeId}/students")
    public List<StudentTransportDto> studentsOnRoute(@PathVariable UUID routeId) {
        return repo.listStudentsOnRoute(routeId);
    }

    // -------------------------- GPS + Trips --------------------------

    public record GpsPingRequest(
        @NotNull UUID vehicleId, @NotNull Instant occurredAt, double lat, double lng, Double speedKmh, Double heading
    ) {}

    @PostMapping("/gps-pings")
    public ResponseEntity<Void> recordPing(@RequestBody GpsPingRequest req) {
        repo.recordGpsPing(req.vehicleId(), req.occurredAt(), req.lat(), req.lng(), req.speedKmh(), req.heading());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/vehicles/{vehicleId}/gps-pings")
    public List<GpsPingDto> recentPings(@PathVariable UUID vehicleId, @RequestParam(defaultValue = "50") int limit) {
        return repo.recentPings(vehicleId, Math.min(limit, 500));
    }

    public record StartTripRequest(@NotNull UUID schoolId, @NotNull UUID routeId, @NotNull UUID vehicleId, @NotNull UUID driverId, @NotBlank String direction) {}

    @PostMapping("/trips/start")
    public TripDto startTrip(@RequestBody StartTripRequest req) {
        return repo.startTrip(req.schoolId(), req.routeId(), req.vehicleId(), req.driverId(), req.direction());
    }

    @PostMapping("/trips/{id}/end")
    public TripDto endTrip(@PathVariable UUID id) {
        return repo.endTrip(id);
    }

    // -------------------------- Geofencing --------------------------

    @GetMapping("/geofence-status")
    public GeofenceStatusDto geofenceStatus(@RequestParam UUID vehicleId, @RequestParam UUID stopId) {
        return repo.checkGeofence(vehicleId, stopId);
    }
}
