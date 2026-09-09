package com.schoolsoft.certificate.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.certificate.api.CertificateDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** SQL for {@code certificate}. The row is written once; only revocation reopens it. */
@Repository
public class CertificateRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CertificateRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final String COLS =
        "id, school_id, student_id, enrolment_id, withdrawal_id, kind, serial_no, issued_on, " +
        "issued_by_staff_id, payload, payload_hash, revoked_at, revoked_reason, superseded_by_id";

    private final RowMapper<CertificateDto> mapper = (rs, i) -> new CertificateDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("student_id")),
        rs.getString("enrolment_id") == null ? null : UUID.fromString(rs.getString("enrolment_id")),
        rs.getString("withdrawal_id") == null ? null : UUID.fromString(rs.getString("withdrawal_id")),
        rs.getString("kind"),
        rs.getString("serial_no"),
        rs.getDate("issued_on").toLocalDate(),
        rs.getString("issued_by_staff_id") == null ? null : UUID.fromString(rs.getString("issued_by_staff_id")),
        readPayload(rs.getString("payload")),
        rs.getString("payload_hash"),
        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
        rs.getString("revoked_reason"),
        rs.getString("superseded_by_id") == null ? null : UUID.fromString(rs.getString("superseded_by_id"))
    );

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String raw) {
        try {
            return raw == null ? Map.of() : json.readValue(raw, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable certificate payload", e);
        }
    }

    /**
     * The payload exactly as stored — the bytes the hash was taken over.
     * Verification uses this rather than re-serialising the parsed map, because
     * a round trip through Jackson can change spacing or number formatting
     * without changing the meaning, and the digest would move with it.
     */
    public String payloadText(UUID id) {
        var rows = jdbc.query("SELECT payload::text FROM certificate WHERE id = ?",
            (rs, i) -> rs.getString(1), id);
        if (rows.isEmpty()) throw new NotFoundException("Certificate not found: " + id);
        return rows.get(0);
    }

    public CertificateDto require(UUID id) {
        var rows = jdbc.query("SELECT " + COLS + " FROM certificate WHERE id = ?", mapper, id);
        if (rows.isEmpty()) throw new NotFoundException("Certificate not found: " + id);
        return rows.get(0);
    }

    public List<CertificateDto> listForStudent(UUID studentId) {
        return jdbc.query("SELECT " + COLS + " FROM certificate WHERE student_id = ? " +
            "ORDER BY issued_on DESC, created_at DESC", mapper, studentId);
    }

    public List<CertificateDto> list(UUID schoolId, String kind) {
        String sql = "SELECT " + COLS + " FROM certificate WHERE school_id = ?"
            + (kind == null ? "" : " AND kind = ?") + " ORDER BY issued_on DESC, created_at DESC";
        return kind == null ? jdbc.query(sql, mapper, schoolId) : jdbc.query(sql, mapper, schoolId, kind);
    }

    /** The live certificate of this kind against an enrolment, if one was issued. */
    public List<CertificateDto> liveFor(UUID enrolmentId, String kind) {
        return jdbc.query("SELECT " + COLS + " FROM certificate " +
            "WHERE enrolment_id = ? AND kind = ? AND revoked_at IS NULL", mapper, enrolmentId, kind);
    }

    public UUID insert(UUID schoolId, UUID studentId, UUID enrolmentId, UUID withdrawalId, String kind,
                       String serialNo, LocalDate issuedOn, UUID issuedByStaffId,
                       Map<String, Object> payload, String hash) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO certificate (id, school_id, student_id, enrolment_id, withdrawal_id, kind, " +
            "  serial_no, issued_on, issued_by_staff_id, payload, payload_hash) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, studentId, enrolmentId, withdrawalId, kind, serialNo,
            Date.valueOf(issuedOn), issuedByStaffId, jsonb(payload), hash);
        return id;
    }

    /**
     * Revoking is the only edit a certificate takes, and the trigger in V031
     * refuses every other one. Zero rows means it was already revoked, which a
     * retry treats as done rather than as a failure.
     */
    public int revoke(UUID id, String reason, UUID staffId) {
        return jdbc.update(
            "UPDATE certificate SET revoked_at = now(), revoked_reason = ?, revoked_by_staff_id = ? " +
            "WHERE id = ? AND revoked_at IS NULL", reason, staffId, id);
    }

    public void markSuperseded(UUID id, UUID bySupersedingId) {
        jdbc.update("UPDATE certificate SET superseded_by_id = ? WHERE id = ?", bySupersedingId, id);
    }

    private PGobject jsonb(Map<String, Object> payload) {
        try {
            PGobject obj = new PGobject();
            // `json`, matching the column: the stored text is what the hash is
            // over, so it must survive the round trip byte for byte.
            obj.setType("json");
            obj.setValue(json.writeValueAsString(payload));
            return obj;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unserialisable certificate payload", e);
        }
    }
}
