package com.schoolsoft.fees.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.fees.api.FeeStructureDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Fee structures and their lines (FEE-01). */
@Repository
public class FeeStructureRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public FeeStructureRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<FeeStructureDto> list(UUID schoolId, UUID academicYearId, UUID gradeId) {
        StringBuilder sql = new StringBuilder(
            "SELECT id, school_id, grade_id, academic_year_id, name, schedule::text AS schedule " +
            "FROM fee_structure WHERE school_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(schoolId);
        if (academicYearId != null) { sql.append(" AND academic_year_id = ?"); args.add(academicYearId); }
        if (gradeId != null) { sql.append(" AND grade_id = ?"); args.add(gradeId); }
        sql.append(" ORDER BY name");
        return jdbc.query(sql.toString(), (rs, i) -> hydrate(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("school_id")),
            UUID.fromString(rs.getString("grade_id")),
            UUID.fromString(rs.getString("academic_year_id")),
            rs.getString("name"), rs.getString("schedule")), args.toArray());
    }

    public FeeStructureDto find(UUID id) {
        var rows = jdbc.query(
            "SELECT id, school_id, grade_id, academic_year_id, name, schedule::text AS schedule " +
            "FROM fee_structure WHERE id = ?",
            (rs, i) -> hydrate(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("school_id")),
                UUID.fromString(rs.getString("grade_id")),
                UUID.fromString(rs.getString("academic_year_id")),
                rs.getString("name"), rs.getString("schedule")),
            id);
        if (rows.isEmpty()) throw new NotFoundException("Fee structure not found: " + id);
        return rows.get(0);
    }

    private FeeStructureDto hydrate(UUID id, UUID schoolId, UUID gradeId, UUID academicYearId,
                                    String name, String scheduleJson) {
        List<FeeStructureDto.Line> lines = jdbc.query(
            "SELECT l.id, l.fee_head_id, h.code, h.name, l.amount, h.gst_rate_pct " +
            "FROM fee_structure_line l JOIN fee_head h ON h.id = l.fee_head_id " +
            "WHERE l.fee_structure_id = ? ORDER BY h.code",
            (rs, i) -> new FeeStructureDto.Line(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("fee_head_id")),
                rs.getString("code"), rs.getString("name"),
                rs.getDouble("amount"), rs.getDouble("gst_rate_pct")),
            id);
        double total = lines.stream().mapToDouble(FeeStructureDto.Line::amount).sum();
        Map<String, Object> schedule;
        try {
            schedule = scheduleJson == null ? Map.of() : json.readValue(scheduleJson, Map.class);
        } catch (Exception e) {
            schedule = Map.of("raw", scheduleJson);
        }
        return new FeeStructureDto(id, schoolId, gradeId, academicYearId, name, schedule, lines, total);
    }

    public record LineInput(UUID feeHeadId, double amount) {}

    public FeeStructureDto create(UUID schoolId, UUID gradeId, UUID academicYearId, String name,
                                  Map<String, Object> schedule, List<LineInput> lines) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO fee_structure (id, school_id, grade_id, academic_year_id, name, schedule) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            id, schoolId, gradeId, academicYearId, name, jsonb(schedule));
        replaceLines(id, lines);
        return find(id);
    }

    public FeeStructureDto replaceLines(UUID structureId, List<LineInput> lines) {
        jdbc.update("DELETE FROM fee_structure_line WHERE fee_structure_id = ?", structureId);
        for (LineInput line : lines == null ? List.<LineInput>of() : lines) {
            jdbc.update(
                "INSERT INTO fee_structure_line (id, fee_structure_id, fee_head_id, amount) " +
                "VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), structureId, line.feeHeadId(), line.amount());
        }
        return find(structureId);
    }

    /**
     * Copies a structure into another academic year. A clone, not a reference:
     * editing next year's tuition must not change what last year's invoices
     * were raised against (FEE-01).
     */
    public FeeStructureDto cloneInto(UUID structureId, UUID targetAcademicYearId, String newName) {
        FeeStructureDto source = find(structureId);
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO fee_structure (id, school_id, grade_id, academic_year_id, name, schedule) " +
            "SELECT ?, school_id, grade_id, ?, ?, schedule FROM fee_structure WHERE id = ?",
            id, targetAcademicYearId, newName == null ? source.name() : newName, structureId);
        jdbc.update(
            "INSERT INTO fee_structure_line (id, fee_structure_id, fee_head_id, amount) " +
            "SELECT gen_random_uuid(), ?, fee_head_id, amount FROM fee_structure_line " +
            "WHERE fee_structure_id = ?",
            id, structureId);
        return find(id);
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
}
