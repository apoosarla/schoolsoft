package com.schoolsoft.device.internal;

import com.schoolsoft.attendance.api.AttendanceMarking;
import com.schoolsoft.device.api.DeviceDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Device registry + hardware event ingestion. Per the design doc's local-agent
 * pattern (§11), the caller (a school-side biometric/RFID bridge) has already
 * resolved the raw device read to a student/staff id — this repository does
 * not own a card/fingerprint-to-person mapping table, only the resulting
 * attendance write.
 *
 * Student events go through {@link AttendanceMarking} rather than a direct
 * INSERT: a gate punch is attendance, and the closed-year and working-day rules
 * that govern a teacher's mark have to govern it too. Writing the row here
 * instead is what made the two paths drift apart in the first place.
 */
@Repository
public class DeviceRepository {

    private final JdbcTemplate jdbc;
    private final AttendanceMarking attendance;

    public DeviceRepository(JdbcTemplate jdbc, AttendanceMarking attendance) {
        this.jdbc = jdbc;
        this.attendance = attendance;
    }

    private static final RowMapper<DeviceDto> MAPPER = (rs, i) -> new DeviceDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id")),
        rs.getString("kind"),
        rs.getString("vendor"),
        rs.getString("model"),
        rs.getString("serial_no"),
        rs.getString("location"),
        rs.getString("assigned_vehicle_id") == null ? null : UUID.fromString(rs.getString("assigned_vehicle_id")),
        rs.getTimestamp("last_seen_at") == null ? null : rs.getTimestamp("last_seen_at").toInstant(),
        rs.getBoolean("is_active")
    );

    private static final String COLS =
        "id, school_id, campus_id, kind, vendor, model, serial_no, location, assigned_vehicle_id, " +
        "last_seen_at, is_active";

    public List<DeviceDto> list(UUID schoolId, String kind, UUID campusId) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM device WHERE school_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(schoolId);
        if (kind != null) {
            sql.append(" AND kind = ?");
            args.add(kind);
        }
        if (campusId != null) {
            sql.append(" AND campus_id = ?");
            args.add(campusId);
        }
        sql.append(" ORDER BY location");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * A device is registered to one campus. When the caller does not name one
     * the school's primary campus stands in (the trigger in V018 does the
     * defaulting), which keeps single-campus schools from having to care.
     */
    public DeviceDto register(
        UUID schoolId, UUID campusId, String kind, String vendor, String model, String serialNo,
        String location, String apiKey
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO device (id, school_id, campus_id, kind, vendor, model, serial_no, location, api_key_hash) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, campusId, kind, vendor, model, serialNo, location, sha256(apiKey)
        );
        return jdbc.queryForObject("SELECT " + COLS + " FROM device WHERE id = ?", MAPPER, id);
    }

    private DeviceDto touchAndGet(UUID deviceId) {
        int updated = jdbc.update("UPDATE device SET last_seen_at = now() WHERE id = ?", deviceId);
        if (updated == 0) throw new NotFoundException("Device not found: " + deviceId);
        return jdbc.queryForObject("SELECT " + COLS + " FROM device WHERE id = ?", MAPPER, deviceId);
    }

    /** Biometric or RFID event for a student — writes a day-level {@code attendance_record}. */
    public DeviceDto ingestStudentEvent(UUID deviceId, UUID schoolId, UUID studentId, UUID sectionId, LocalDate onDate, String source) {
        DeviceDto device = touchAndGet(deviceId);
        attendance.markDay(schoolId, studentId, sectionId, onDate, "present", source);
        return device;
    }

    /** Biometric event for staff — writes/updates {@code staff_attendance} in-time. */
    public DeviceDto ingestStaffEvent(UUID deviceId, UUID schoolId, UUID staffId, LocalDate onDate, boolean checkIn) {
        DeviceDto device = touchAndGet(deviceId);
        if (checkIn) {
            jdbc.update(
                "INSERT INTO staff_attendance (id, school_id, staff_id, on_date, in_at, source) VALUES (?, ?, ?, ?, now(), 'biometric') " +
                "ON CONFLICT (staff_id, on_date) DO UPDATE SET in_at = COALESCE(staff_attendance.in_at, EXCLUDED.in_at)",
                UUID.randomUUID(), schoolId, staffId, Date.valueOf(onDate)
            );
        } else {
            jdbc.update(
                "INSERT INTO staff_attendance (id, school_id, staff_id, on_date, out_at, source) VALUES (?, ?, ?, ?, now(), 'biometric') " +
                "ON CONFLICT (staff_id, on_date) DO UPDATE SET out_at = now()",
                UUID.randomUUID(), schoolId, staffId, Date.valueOf(onDate)
            );
        }
        return device;
    }

    private static String sha256(String value) {
        if (value == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
