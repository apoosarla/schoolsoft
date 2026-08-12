package com.schoolsoft.boardintegration.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.boardintegration.api.BoardExportJobDto;
import com.schoolsoft.enrolment.api.StudentSubjectDto;
import com.schoolsoft.enrolment.api.SubjectSetResolver;
import com.schoolsoft.platform.web.NotFoundException;
import java.util.LinkedHashMap;
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
    private final SubjectSetResolver subjectSets;

    public BoardExportRepository(JdbcTemplate jdbc, ObjectMapper json, SubjectSetResolver subjectSets) {
        this.jdbc = jdbc;
        this.json = json;
        this.subjectSets = subjectSets;
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

    /**
     * Builds the candidate list when the caller names a section or a student,
     * with each candidate's own subject set rather than their section's — a
     * cohort where two students sit different option blocks is the normal case
     * for a Cambridge school, not an edge case (INT-02).
     */
    public BoardExportJobDto enqueue(
        UUID schoolId, String boardCode, String exportType, UUID academicYearId, UUID sectionId, UUID studentId, Object requestPayload
    ) {
        Object payload = requestPayload;
        if (sectionId != null || studentId != null) {
            Map<String, Object> built = new LinkedHashMap<>();
            if (requestPayload instanceof Map<?, ?> given) {
                given.forEach((k, v) -> built.put(String.valueOf(k), v));
            }
            built.put("candidates", candidates(sectionId, studentId));
            payload = built;
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO board_export_job (id, school_id, board_code, export_type, academic_year_id, section_id, student_id, request_payload) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, boardCode, exportType, academicYearId, sectionId, studentId, jsonb(payload)
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

        // The adapter is a stub, but the schema check is real: a board rejects a
        // candidate with no name, no date of birth or no subjects, and finding
        // that out here beats finding it out from the board.
        List<String> problems = validate(id);
        if (!problems.isEmpty()) {
            return fail(id, "Payload failed board schema validation: " + String.join("; ", problems));
        }

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

    // ------------------------------------------------------------- payloads

    private List<Map<String, Object>> candidates(UUID sectionId, UUID studentId) {
        String sql =
            "SELECT e.id AS enrolment_id, st.id AS student_id, st.admission_no, " +
            "       (st.first_name || ' ' || COALESCE(st.last_name, '')) AS full_name, st.dob, e.roll_no " +
            "FROM enrolment e JOIN student st ON st.id = e.student_id " +
            "WHERE e.status = 'active' AND " + (sectionId != null ? "e.section_id = ?" : "e.student_id = ?") +
            " ORDER BY e.roll_no, st.admission_no";

        return jdbc.query(sql, (rs, i) -> {
            UUID enrolmentId = UUID.fromString(rs.getString("enrolment_id"));
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("studentId", rs.getString("student_id"));
            candidate.put("admissionNo", rs.getString("admission_no"));
            candidate.put("name", rs.getString("full_name").trim());
            candidate.put("dob", rs.getDate("dob") == null ? null : rs.getDate("dob").toString());
            candidate.put("rollNo", rs.getString("roll_no"));
            candidate.put("subjects", subjectSets.forEnrolment(enrolmentId, java.time.LocalDate.now()).stream()
                .map(StudentSubjectDto::subjectCode).toList());
            return candidate;
        }, sectionId != null ? sectionId : studentId);
    }

    /** Minimal board schema: every candidate needs an identity and at least one subject. */
    private List<String> validate(UUID jobId) {
        String payload = jdbc.queryForObject(
            "SELECT request_payload::text FROM board_export_job WHERE id = ?", String.class, jobId);
        List<String> problems = new java.util.ArrayList<>();
        try {
            var root = json.readTree(payload == null ? "{}" : payload);
            var candidates = root.get("candidates");
            if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
                problems.add("no candidates in payload");
                return problems;
            }
            for (var candidate : candidates) {
                String who = candidate.hasNonNull("admissionNo")
                    ? candidate.get("admissionNo").asText() : "(no admission no)";
                if (!candidate.hasNonNull("admissionNo")) problems.add(who + ": missing admissionNo");
                if (!candidate.hasNonNull("name") || candidate.get("name").asText().isBlank()) {
                    problems.add(who + ": missing name");
                }
                if (!candidate.hasNonNull("dob")) problems.add(who + ": missing dob");
                var subjects = candidate.get("subjects");
                if (subjects == null || !subjects.isArray() || subjects.isEmpty()) {
                    problems.add(who + ": no subjects");
                }
            }
        } catch (Exception e) {
            problems.add("payload is not readable JSON: " + e.getMessage());
        }
        return problems;
    }
}
