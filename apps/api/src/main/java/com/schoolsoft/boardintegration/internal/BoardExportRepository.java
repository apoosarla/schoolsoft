package com.schoolsoft.boardintegration.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.boardintegration.api.BoardExportJobDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class BoardExportRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public BoardExportRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private PGobject jsonb(Object value) {
        try {
            PGobject o = new PGobject();
            o.setType("jsonb");
            o.setValue(json.writeValueAsString(value == null ? Map.of() : value));
            return o;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final RowMapper<BoardExportJobDto> MAPPER = (rs, i) -> new BoardExportJobDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("board_code"),
        rs.getString("export_type"),
        rs.getString("academic_year_id") == null ? null : UUID.fromString(rs.getString("academic_year_id")),
        rs.getString("section_id") == null ? null : UUID.fromString(rs.getString("section_id")),
        rs.getString("student_id") == null ? null : UUID.fromString(rs.getString("student_id")),
        rs.getString("status"),
        rs.getString("error_message"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()
    );

    private static final String COLS =
        "id, school_id, board_code, export_type, academic_year_id, section_id, student_id, status, error_message, created_at, completed_at";

    public List<BoardExportJobDto> list(UUID schoolId, String status) {
        String sql = "SELECT " + COLS + " FROM board_export_job WHERE school_id = ?" +
            (status == null ? "" : " AND status = ?") + " ORDER BY created_at DESC";
        return status == null ? jdbc.query(sql, MAPPER, schoolId) : jdbc.query(sql, MAPPER, schoolId, status);
    }

    public Optional<BoardExportJobDto> find(UUID id) {
        var rows = jdbc.query("SELECT " + COLS + " FROM board_export_job WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public BoardExportJobDto enqueue(
        UUID schoolId, String boardCode, String exportType, UUID academicYearId, UUID sectionId, UUID studentId, Object requestPayload
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO board_export_job (id, school_id, board_code, export_type, academic_year_id, section_id, student_id, request_payload) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, boardCode, exportType, academicYearId, sectionId, studentId, jsonb(requestPayload)
        );
        return find(id).orElseThrow();
    }

    /**
     * Stands in for the real CIE Direct / UDISE+ HTTP adapter (no sandbox
     * credentials available in this environment). Moves the job through
     * {@code queued -> processing -> completed} synchronously with a canned
     * result so the job lifecycle itself is real and testable.
     */
    public BoardExportJobDto process(UUID id) {
        var job = find(id).orElseThrow(() -> new NotFoundException("Export job not found: " + id));
        if (!"queued".equals(job.status())) {
            throw new IllegalArgumentException("Job is not queued (status=" + job.status() + ")");
        }
        jdbc.update("UPDATE board_export_job SET status = 'processing' WHERE id = ?", id);

        Map<String, Object> result = Map.of(
            "adapter", "stub",
            "exportType", job.exportType(),
            "acknowledgedAt", java.time.Instant.now().toString()
        );
        jdbc.update(
            "UPDATE board_export_job SET status = 'completed', result_payload = ?, completed_at = now() WHERE id = ?",
            jsonb(result), id
        );
        return find(id).orElseThrow();
    }

    public BoardExportJobDto fail(UUID id, String errorMessage) {
        int updated = jdbc.update(
            "UPDATE board_export_job SET status = 'failed', error_message = ?, completed_at = now() WHERE id = ?",
            errorMessage, id
        );
        if (updated == 0) throw new NotFoundException("Export job not found: " + id);
        return find(id).orElseThrow();
    }
}
