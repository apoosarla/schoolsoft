package com.schoolsoft.assessment.internal.strategy;

import com.schoolsoft.assessment.api.GradeScaleDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * A school's grade boundaries.
 *
 * V022 seeded one per school from its board. A school created afterwards gets
 * the same seed on first read rather than a null scale, because the first time
 * anybody asks is while a report card is being generated and "this school has
 * no grade scale" is not a useful thing to tell a class teacher.
 */
@Repository
public class GradeScaleRepository {

    private final JdbcTemplate jdbc;

    public GradeScaleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Object[]> SCALE = (rs, i) -> new Object[]{
        UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("school_id")),
        rs.getString("code"), rs.getString("name"), rs.getString("strategy_code"),
        rs.getDouble("pass_pct")
    };

    /** The scale for a school's strategy, falling back to its default scale. */
    public GradeScaleDto forSchool(UUID schoolId, String strategyCode) {
        List<Object[]> rows = jdbc.query(
            "SELECT id, school_id, code, name, strategy_code, pass_pct FROM grade_scale " +
            "WHERE school_id = ? ORDER BY (strategy_code = ?) DESC, is_default DESC, created_at LIMIT 1",
            SCALE, schoolId, strategyCode == null ? "" : strategyCode);
        if (rows.isEmpty()) {
            seedDefault(schoolId);
            rows = jdbc.query(
                "SELECT id, school_id, code, name, strategy_code, pass_pct FROM grade_scale " +
                "WHERE school_id = ? ORDER BY is_default DESC, created_at LIMIT 1",
                SCALE, schoolId);
        }
        Object[] row = rows.get(0);
        UUID scaleId = (UUID) row[0];
        List<GradeScaleDto.Band> bands = jdbc.query(
            "SELECT grade, min_pct, max_pct, grade_point FROM grade_band " +
            "WHERE grade_scale_id = ? ORDER BY min_pct DESC",
            (rs, i) -> new GradeScaleDto.Band(
                rs.getString("grade"), rs.getDouble("min_pct"), rs.getDouble("max_pct"),
                com.schoolsoft.platform.db.Jdbc.nullableDouble(rs, "grade_point")),
            scaleId);
        return new GradeScaleDto(scaleId, (UUID) row[1], (String) row[2], (String) row[3],
            (String) row[4], (Double) row[5], bands);
    }

    /**
     * Same seed the migration applied, for a school provisioned since. Board
     * code decides: CBSE's A1–E with a 33% pass, Cambridge's A*–U with 40%.
     */
    private void seedDefault(UUID schoolId) {
        String board = jdbc.query("SELECT board_code FROM school WHERE id = ?",
            (rs, i) -> rs.getString("board_code"), schoolId).stream().findFirst().orElse("CBSE");
        boolean cie = "CIE".equals(board);

        UUID scaleId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO grade_scale (id, school_id, code, name, strategy_code, pass_pct, is_default) " +
            "VALUES (?, ?, ?, ?, ?, ?, TRUE) ON CONFLICT (school_id, code) DO NOTHING",
            scaleId, schoolId,
            cie ? "CIE_ASTAR_E" : "CBSE_A1_E",
            cie ? "Cambridge A*–U" : "CBSE A1–E",
            cie ? "CIE-IGCSE" : "CBSE-CCE-2024",
            cie ? 40 : 33);

        List<Object[]> bands = new ArrayList<>();
        if (cie) {
            bands.add(new Object[]{"A*", 90, 100, 8});
            bands.add(new Object[]{"A", 80, 89.99, 7});
            bands.add(new Object[]{"B", 70, 79.99, 6});
            bands.add(new Object[]{"C", 60, 69.99, 5});
            bands.add(new Object[]{"D", 50, 59.99, 4});
            bands.add(new Object[]{"E", 40, 49.99, 3});
            bands.add(new Object[]{"U", 0, 39.99, 0});
        } else {
            bands.add(new Object[]{"A1", 91, 100, 10});
            bands.add(new Object[]{"A2", 81, 90.99, 9});
            bands.add(new Object[]{"B1", 71, 80.99, 8});
            bands.add(new Object[]{"B2", 61, 70.99, 7});
            bands.add(new Object[]{"C1", 51, 60.99, 6});
            bands.add(new Object[]{"C2", 41, 50.99, 5});
            bands.add(new Object[]{"D", 33, 40.99, 4});
            bands.add(new Object[]{"E", 0, 32.99, 0});
        }
        List<Object[]> rows = new ArrayList<>();
        for (Object[] band : bands) {
            rows.add(new Object[]{UUID.randomUUID(), scaleId, band[0], band[1], band[2], band[3]});
        }
        jdbc.batchUpdate(
            "INSERT INTO grade_band (id, grade_scale_id, grade, min_pct, max_pct, grade_point) " +
            "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING", rows);
    }
}
