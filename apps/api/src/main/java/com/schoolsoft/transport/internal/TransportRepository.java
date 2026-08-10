package com.schoolsoft.transport.internal;

import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.transport.api.DriverDto;
import com.schoolsoft.transport.api.GeofenceStatusDto;
import com.schoolsoft.transport.api.GpsPingDto;
import com.schoolsoft.transport.api.StudentTransportDto;
import com.schoolsoft.transport.api.TransportRouteDto;
import com.schoolsoft.transport.api.TransportStopDto;
import com.schoolsoft.transport.api.TripDto;
import com.schoolsoft.transport.api.VehicleDto;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TransportRepository {

    private final JdbcTemplate jdbc;
    public TransportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // -------------------------- Vehicles --------------------------

    private static final RowMapper<VehicleDto> VEHICLE_MAPPER = (rs, i) -> new VehicleDto(
        UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("school_id")),
        rs.getString("registration_no"), rs.getString("model"), (Integer) rs.getObject("capacity"), rs.getBoolean("is_active")
    );

    public List<VehicleDto> listVehicles(UUID schoolId) {
        return jdbc.query(
            "SELECT id, school_id, registration_no, model, capacity, is_active FROM vehicle WHERE school_id = ? ORDER BY registration_no",
            VEHICLE_MAPPER, schoolId
        );
    }

    public VehicleDto createVehicle(UUID schoolId, String registrationNo, String model, Integer capacity) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO vehicle (id, school_id, registration_no, model, capacity) VALUES (?, ?, ?, ?, ?)",
            id, schoolId, registrationNo, model, capacity
        );
        return jdbc.queryForObject(
            "SELECT id, school_id, registration_no, model, capacity, is_active FROM vehicle WHERE id = ?", VEHICLE_MAPPER, id
        );
    }

    // -------------------------- Drivers --------------------------

    private static final RowMapper<DriverDto> DRIVER_MAPPER = (rs, i) -> new DriverDto(
        UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("school_id")),
        rs.getString("name"), rs.getString("phone"), rs.getString("license_no"), rs.getBoolean("is_active")
    );

    public List<DriverDto> listDrivers(UUID schoolId, UUID staffId) {
        if (staffId != null) {
            return jdbc.query(
                "SELECT id, school_id, name, phone, license_no, is_active FROM driver " +
                "WHERE school_id = ? AND staff_id = ? ORDER BY name",
                DRIVER_MAPPER, schoolId, staffId
            );
        }
        return jdbc.query(
            "SELECT id, school_id, name, phone, license_no, is_active FROM driver WHERE school_id = ? ORDER BY name",
            DRIVER_MAPPER, schoolId
        );
    }

    public DriverDto createDriver(UUID schoolId, String name, String phone, String licenseNo) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO driver (id, school_id, name, phone, license_no) VALUES (?, ?, ?, ?, ?)",
            id, schoolId, name, phone, licenseNo
        );
        return jdbc.queryForObject(
            "SELECT id, school_id, name, phone, license_no, is_active FROM driver WHERE id = ?", DRIVER_MAPPER, id
        );
    }

    // -------------------------- Routes + Stops --------------------------

    private static final RowMapper<TransportRouteDto> ROUTE_MAPPER = (rs, i) -> new TransportRouteDto(
        UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("school_id")),
        rs.getString("code"), rs.getString("name"), rs.getString("direction"), rs.getBoolean("is_active")
    );

    public List<TransportRouteDto> listRoutes(UUID schoolId) {
        return jdbc.query(
            "SELECT id, school_id, code, name, direction, is_active FROM transport_route WHERE school_id = ? ORDER BY code",
            ROUTE_MAPPER, schoolId
        );
    }

    public TransportRouteDto createRoute(UUID schoolId, String code, String name, String direction) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO transport_route (id, school_id, code, name, direction) VALUES (?, ?, ?, ?, ?)",
            id, schoolId, code, name, direction == null ? "pickup" : direction
        );
        return jdbc.queryForObject(
            "SELECT id, school_id, code, name, direction, is_active FROM transport_route WHERE id = ?", ROUTE_MAPPER, id
        );
    }

    private static final RowMapper<TransportStopDto> STOP_MAPPER = (rs, i) -> new TransportStopDto(
        UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("route_id")), rs.getString("name"),
        rs.getInt("sort_order"), com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "lat"),
        com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "lng"), com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "fee")
    );

    public List<TransportStopDto> listStops(UUID routeId) {
        return jdbc.query(
            "SELECT id, route_id, name, sort_order, lat, lng, fee FROM transport_stop WHERE route_id = ? ORDER BY sort_order",
            STOP_MAPPER, routeId
        );
    }

    public TransportStopDto addStop(UUID routeId, String name, int sortOrder, Double lat, Double lng, Double fee) {
        UUID schoolId = jdbc.queryForObject(
            "SELECT school_id FROM transport_route WHERE id = ?", (rs, i) -> UUID.fromString(rs.getString("school_id")), routeId
        );
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO transport_stop (id, school_id, route_id, name, sort_order, lat, lng, fee) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, routeId, name, sortOrder, lat, lng, fee
        );
        return jdbc.queryForObject(
            "SELECT id, route_id, name, sort_order, lat, lng, fee FROM transport_stop WHERE id = ?", STOP_MAPPER, id
        );
    }

    // -------------------------- Student assignment --------------------------

    public StudentTransportDto assignStudent(UUID schoolId, UUID studentId, UUID routeId, UUID stopId, LocalDate startsOn) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO student_transport (id, school_id, student_id, route_id, stop_id, starts_on) VALUES (?, ?, ?, ?, ?, ?)",
            id, schoolId, studentId, routeId, stopId, Date.valueOf(startsOn)
        );
        return jdbc.queryForObject(
            "SELECT id, student_id, route_id, stop_id, starts_on, ends_on FROM student_transport WHERE id = ?",
            (rs, i) -> new StudentTransportDto(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("student_id")),
                UUID.fromString(rs.getString("route_id")), UUID.fromString(rs.getString("stop_id")),
                rs.getDate("starts_on").toLocalDate(), rs.getDate("ends_on") == null ? null : rs.getDate("ends_on").toLocalDate()
            ),
            id
        );
    }

    public List<StudentTransportDto> listStudentsOnRoute(UUID routeId) {
        return jdbc.query(
            "SELECT id, student_id, route_id, stop_id, starts_on, ends_on FROM student_transport WHERE route_id = ? AND ends_on IS NULL",
            (rs, i) -> new StudentTransportDto(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("student_id")),
                UUID.fromString(rs.getString("route_id")), UUID.fromString(rs.getString("stop_id")),
                rs.getDate("starts_on").toLocalDate(), rs.getDate("ends_on") == null ? null : rs.getDate("ends_on").toLocalDate()
            ),
            routeId
        );
    }

    // -------------------------- GPS + Trips --------------------------

    /** Vendor-agnostic ingestion point — any GPS provider (Teltonika, Concox, iCue, etc.) posts here. */
    public void recordGpsPing(UUID vehicleId, Instant occurredAt, double lat, double lng, Double speedKmh, Double heading) {
        jdbc.update(
            "INSERT INTO gps_ping (vehicle_id, occurred_at, lat, lng, speed_kmh, heading) VALUES (?, ?, ?, ?, ?, ?)",
            vehicleId, Timestamp.from(occurredAt), lat, lng, speedKmh, heading
        );
    }

    public List<GpsPingDto> recentPings(UUID vehicleId, int limit) {
        return jdbc.query(
            "SELECT vehicle_id, occurred_at, lat, lng, speed_kmh, heading FROM gps_ping WHERE vehicle_id = ? " +
            "ORDER BY occurred_at DESC LIMIT ?",
            (rs, i) -> new GpsPingDto(
                UUID.fromString(rs.getString("vehicle_id")), rs.getTimestamp("occurred_at").toInstant(),
                rs.getDouble("lat"), rs.getDouble("lng"), com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "speed_kmh"),
                com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "heading")
            ),
            vehicleId, limit
        );
    }

    private static final RowMapper<TripDto> TRIP_MAPPER = (rs, i) -> new TripDto(
        UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("route_id")), UUID.fromString(rs.getString("vehicle_id")),
        UUID.fromString(rs.getString("driver_id")), rs.getString("direction"),
        rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant()
    );

    public TripDto startTrip(UUID schoolId, UUID routeId, UUID vehicleId, UUID driverId, String direction) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO trip (id, school_id, route_id, vehicle_id, driver_id, direction, started_at) VALUES (?, ?, ?, ?, ?, ?, now())",
            id, schoolId, routeId, vehicleId, driverId, direction
        );
        return jdbc.queryForObject(
            "SELECT id, school_id, route_id, vehicle_id, driver_id, direction, started_at, ended_at FROM trip WHERE id = ?",
            TRIP_MAPPER, id
        );
    }

    public TripDto endTrip(UUID id) {
        int updated = jdbc.update("UPDATE trip SET ended_at = now() WHERE id = ?", id);
        if (updated == 0) throw new NotFoundException("Trip not found: " + id);
        return jdbc.queryForObject(
            "SELECT id, school_id, route_id, vehicle_id, driver_id, direction, started_at, ended_at FROM trip WHERE id = ?",
            TRIP_MAPPER, id
        );
    }

    // -------------------------- Geofencing --------------------------

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    /**
     * Compares the vehicle's most recent GPS ping against a stop's geofence
     * (per §5.5). Distance via the Haversine formula — accurate enough at
     * stop-radius scale (tens to low-hundreds of metres) without a PostGIS
     * dependency this schema doesn't otherwise need.
     */
    public GeofenceStatusDto checkGeofence(UUID vehicleId, UUID stopId) {
        var pingRows = jdbc.query(
            "SELECT lat, lng, occurred_at FROM gps_ping WHERE vehicle_id = ? ORDER BY occurred_at DESC LIMIT 1",
            (rs, i) -> new double[]{rs.getDouble("lat"), rs.getDouble("lng")},
            vehicleId
        );
        if (pingRows.isEmpty()) throw new NotFoundException("No GPS pings recorded for vehicle: " + vehicleId);
        double[] ping = pingRows.get(0);
        Instant asOf = jdbc.queryForObject(
            "SELECT occurred_at FROM gps_ping WHERE vehicle_id = ? ORDER BY occurred_at DESC LIMIT 1",
            (rs, i) -> rs.getTimestamp("occurred_at").toInstant(), vehicleId
        );

        var stopRows = jdbc.query(
            "SELECT lat, lng, geofence_radius_m FROM transport_stop WHERE id = ?",
            (rs, i) -> new Object[]{
                com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "lat"),
                com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "lng"),
                rs.getInt("geofence_radius_m")
            },
            stopId
        );
        if (stopRows.isEmpty()) throw new NotFoundException("Stop not found: " + stopId);
        Object[] stop = stopRows.get(0);
        if (stop[0] == null || stop[1] == null) {
            throw new IllegalArgumentException("Stop has no coordinates configured: " + stopId);
        }
        double stopLat = (double) stop[0];
        double stopLng = (double) stop[1];
        int radiusM = (int) stop[2];

        double distance = haversineMeters(ping[0], ping[1], stopLat, stopLng);
        return new GeofenceStatusDto(vehicleId, stopId, distance <= radiusM, distance, radiusM, asOf);
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }
}
