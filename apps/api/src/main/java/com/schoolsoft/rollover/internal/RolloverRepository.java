package com.schoolsoft.rollover.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.rollover.api.RolloverAllocationDto;
import com.schoolsoft.rollover.api.RolloverRunDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Rows and reads for {@link RolloverService}; the rules live there, not here. */
@Repository
public class RolloverRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RolloverRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // -------------------------------------------------------------------- run

    private static final String RUN_SELECT =
        "SELECT r.id, r.school_id, r.from_academic_year_id, fay.code AS from_code, " +
        "       r.to_academic_year_id, tay.code AS to_code, tay.status AS to_status, " +
        "       tay.is_current AS to_is_current, r.run_key, r.state, r.batch_size, r.batches_done, " +
        "       r.stats, r.started_by_staff_id, r.created_at, r.committed_at, r.rolled_back_at " +
        "FROM rollover_run r " +
        "JOIN academic_year fay ON fay.id = r.from_academic_year_id " +
        "JOIN academic_year tay ON tay.id = r.to_academic_year_id ";

    private final RowMapper<RolloverRunDto> RUN = (rs, i) -> new RolloverRunDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("from_academic_year_id")),
        rs.getString("from_code"),
        UUID.fromString(rs.getString("to_academic_year_id")),
        rs.getString("to_code"),
        rs.getString("to_status"),
        rs.getBoolean("to_is_current"),
        rs.getString("run_key"),
        rs.getString("state"),
        rs.getInt("batch_size"),
        rs.getInt("batches_done"),
        readStats(rs.getString("stats")),
        rs.getString("started_by_staff_id") == null ? null
            : UUID.fromString(rs.getString("started_by_staff_id")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("committed_at") == null ? null : rs.getTimestamp("committed_at").toInstant(),
        rs.getTimestamp("rolled_back_at") == null ? null : rs.getTimestamp("rolled_back_at").toInstant()
    );

    @SuppressWarnings("unchecked")
    private Map<String, Object> readStats(String raw) {
        try {
            return raw == null ? Map.of() : json.readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public UUID insertRun(UUID schoolId, UUID fromAy, UUID toAy, String runKey, int batchSize,
                          UUID startedByStaffId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO rollover_run (id, school_id, from_academic_year_id, to_academic_year_id, " +
            "  run_key, batch_size, started_by_staff_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, fromAy, toAy, runKey, batchSize, startedByStaffId);
        return id;
    }

    public RolloverRunDto findRun(UUID id) {
        var rows = jdbc.query(RUN_SELECT + "WHERE r.id = ?", RUN, id);
        if (rows.isEmpty()) throw new NotFoundException("Rollover run not found: " + id);
        return rows.get(0);
    }

    public java.util.Optional<RolloverRunDto> findByRunKey(UUID schoolId, String runKey) {
        var rows = jdbc.query(RUN_SELECT + "WHERE r.school_id = ? AND r.run_key = ?", RUN, schoolId, runKey);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    public List<RolloverRunDto> listRuns(UUID schoolId) {
        return jdbc.query(RUN_SELECT + "WHERE r.school_id = ? ORDER BY r.created_at DESC", RUN, schoolId);
    }

    public void setState(UUID runId, String state) {
        jdbc.update("UPDATE rollover_run SET state = ? WHERE id = ?", state, runId);
    }

    public void markCommitted(UUID runId) {
        jdbc.update("UPDATE rollover_run SET state = 'committed', committed_at = now() WHERE id = ?", runId);
    }

    public void markRolledBack(UUID runId) {
        jdbc.update(
            "UPDATE rollover_run SET state = 'rolled_back', rolled_back_at = now(), committed_at = NULL " +
            "WHERE id = ?", runId);
    }

    public void mergeStats(UUID runId, Map<String, Object> stats) {
        try {
            jdbc.update("UPDATE rollover_run SET stats = stats || ?::jsonb WHERE id = ?",
                json.writeValueAsString(stats), runId);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write rollover stats", e);
        }
    }

    public void bumpBatches(UUID runId, int batches) {
        jdbc.update("UPDATE rollover_run SET batches_done = batches_done + ? WHERE id = ?", batches, runId);
    }

    // ------------------------------------------------------------- allocation

    private static final String ALLOC_SELECT =
        "SELECT a.id, a.rollover_run_id, a.student_id, " +
        "       (st.first_name || ' ' || COALESCE(st.last_name, '')) AS student_name, st.admission_no, " +
        "       a.from_enrolment_id, a.from_section_id, (fg.code || '-' || fs.code) AS from_label, " +
        "       a.decision, a.to_section_id, (tg.code || '-' || ts.code) AS to_label, a.roll_no, " +
        "       a.over_capacity_reason, a.state, a.note, a.new_enrolment_id, a.batch_no, a.applied_at " +
        "FROM rollover_allocation a " +
        "JOIN student st ON st.id = a.student_id " +
        "JOIN section fs ON fs.id = a.from_section_id JOIN grade fg ON fg.id = fs.grade_id " +
        "LEFT JOIN section ts ON ts.id = a.to_section_id LEFT JOIN grade tg ON tg.id = ts.grade_id ";

    private static final RowMapper<RolloverAllocationDto> ALLOC = (rs, i) -> new RolloverAllocationDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("rollover_run_id")),
        UUID.fromString(rs.getString("student_id")),
        rs.getString("student_name").trim(),
        rs.getString("admission_no"),
        UUID.fromString(rs.getString("from_enrolment_id")),
        UUID.fromString(rs.getString("from_section_id")),
        rs.getString("from_label"),
        rs.getString("decision"),
        rs.getString("to_section_id") == null ? null : UUID.fromString(rs.getString("to_section_id")),
        rs.getString("to_label"),
        rs.getString("roll_no"),
        rs.getString("over_capacity_reason"),
        rs.getString("state"),
        rs.getString("note"),
        rs.getString("new_enrolment_id") == null ? null : UUID.fromString(rs.getString("new_enrolment_id")),
        rs.getInt("batch_no"),
        rs.getTimestamp("applied_at") == null ? null : rs.getTimestamp("applied_at").toInstant()
    );

    public void deletePlannedAllocations(UUID runId) {
        jdbc.update("DELETE FROM rollover_allocation WHERE rollover_run_id = ? AND state <> 'applied'", runId);
    }

    public void insertAllocations(List<Object[]> rows) {
        jdbc.batchUpdate(
            "INSERT INTO rollover_allocation (id, rollover_run_id, school_id, student_id, from_enrolment_id, " +
            "  from_section_id, decision, to_section_id, state, note, batch_no) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
    }

    public List<RolloverAllocationDto> listAllocations(UUID runId, String state) {
        String sql = ALLOC_SELECT + "WHERE a.rollover_run_id = ?"
            + (state == null ? "" : " AND a.state = ?") + " ORDER BY a.batch_no, st.admission_no";
        return state == null
            ? jdbc.query(sql, ALLOC, runId)
            : jdbc.query(sql, ALLOC, runId, state);
    }

    public RolloverAllocationDto findAllocation(UUID id) {
        var rows = jdbc.query(ALLOC_SELECT + "WHERE a.id = ?", ALLOC, id);
        if (rows.isEmpty()) throw new NotFoundException("Rollover allocation not found: " + id);
        return rows.get(0);
    }

    public int countAllocations(UUID runId, String state) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM rollover_allocation WHERE rollover_run_id = ? AND state = ?",
            Integer.class, runId, state);
        return n == null ? 0 : n;
    }

    /**
     * The next slice of work, ordered so a resumed commit continues where it
     * stopped. Children with nowhere to go are left out — they are a question
     * for the school, and including them would hand commit the same unusable
     * rows on every pass.
     */
    public List<RolloverAllocationDto> nextPlannedBatch(UUID runId, int limit) {
        return jdbc.query(ALLOC_SELECT + "WHERE a.rollover_run_id = ? AND a.state = 'planned' " +
            "  AND (a.to_section_id IS NOT NULL OR a.decision = 'graduate') " +
            "ORDER BY a.batch_no, st.admission_no LIMIT " + limit, ALLOC, runId);
    }

    public void updateAllocationTarget(UUID id, UUID toSectionId, String rollNo,
                                       String overCapacityReason, String note, String state) {
        jdbc.update(
            "UPDATE rollover_allocation SET to_section_id = ?, roll_no = ?, over_capacity_reason = ?, " +
            "  note = ?, state = ? WHERE id = ?",
            toSectionId, rollNo, overCapacityReason, note, state, id);
    }

    public void markAllocationApplied(UUID id, UUID newEnrolmentId) {
        jdbc.update(
            "UPDATE rollover_allocation SET state = 'applied', new_enrolment_id = ?, applied_at = now() " +
            "WHERE id = ?", newEnrolmentId, id);
    }

    public void resetAllocation(UUID id) {
        jdbc.update(
            "UPDATE rollover_allocation SET state = 'planned', new_enrolment_id = NULL, applied_at = NULL " +
            "WHERE id = ?", id);
    }

    // --------------------------------------------------------------- artifact

    public record Artifact(UUID id, UUID allocationId, String kind, UUID rowId, String priorState) {}

    public void recordArtifact(UUID runId, UUID allocationId, UUID schoolId, String kind, UUID rowId,
                               String priorState) {
        jdbc.update(
            "INSERT INTO rollover_artifact (id, rollover_run_id, rollover_allocation_id, school_id, kind, " +
            "  row_id, prior_state) VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (rollover_run_id, kind, row_id) DO NOTHING",
            UUID.randomUUID(), runId, allocationId, schoolId, kind, rowId, priorState);
    }

    /** Newest first: a roll-back undoes in the reverse of the order things were made. */
    public List<Artifact> artifactsOf(UUID runId) {
        return jdbc.query(
            "SELECT id, rollover_allocation_id, kind, row_id, prior_state FROM rollover_artifact " +
            "WHERE rollover_run_id = ? ORDER BY created_at DESC, id DESC",
            (rs, i) -> new Artifact(
                UUID.fromString(rs.getString("id")),
                rs.getString("rollover_allocation_id") == null ? null
                    : UUID.fromString(rs.getString("rollover_allocation_id")),
                rs.getString("kind"),
                UUID.fromString(rs.getString("row_id")),
                rs.getString("prior_state")),
            runId);
    }

    public void deleteArtifacts(UUID runId) {
        jdbc.update("DELETE FROM rollover_artifact WHERE rollover_run_id = ?", runId);
    }
}
