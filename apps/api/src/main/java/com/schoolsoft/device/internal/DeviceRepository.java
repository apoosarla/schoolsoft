package com.schoolsoft.device.internal;

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
 * attendance write. Mirrors the direct-SQL-against-shared-tables convention
 * already used by AdmissionsRepository rather than adding a Java dependency
 * on the attendance module's internal package.
 */
@Repository
public class DeviceRepository {

    private final JdbcTemplate jdbc;
    public DeviceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<DeviceDto> MAPPER = (rs, i) -> new DeviceDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
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
        "id, school_id, kind, vendor, model, serial_no, location, assigned_vehicle_id, last_seen_at, is_active";

    public List<DeviceDto> list(UUID schoolId, String kind) {
        String sql = "SELECT " + COLS + " FROM device WHERE school_id = ?" + (kind == null ? "" : " AND kind = ?") + " ORDER BY location";
        return kind == null ? jdbc.query(sql, MAPPER, schoolId) : jdbc.query(sql, MAPPER, schoolId, kind);
    }

    public DeviceDto register(
        UUID schoolId, String kind, String vendor, String model, String serialNo, String location, String apiKey
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO device (id, school_id, kind, vendor, model, serial_no, location, api_key_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, kind, vendor, model, serialNo, location, sha256(apiKey)
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
        jdbc.update(
            "INSERT INTO attendance_record (id, school_id, student_id, section_id, on_date, status, source) " +
            "VALUES (?, ?, ?, ?, ?, 'present', ?) " +
            "ON CONFLICT (student_id, on_date) WHERE period_no IS NULL DO UPDATE SET " +
            "  status = 'present', source = EXCLUDED.source, marked_at = now()",
            UUID.randomUUID(), schoolId, studentId, sectionId, Date.valueOf(onDate), source
        );
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
