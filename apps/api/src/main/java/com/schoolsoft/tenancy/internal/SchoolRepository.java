package com.schoolsoft.tenancy.internal;

import com.schoolsoft.tenancy.api.AcademicYearDto;
import com.schoolsoft.tenancy.api.GradeDto;
import com.schoolsoft.tenancy.api.SchoolDto;
import com.schoolsoft.tenancy.api.SectionDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SchoolRepository {

    private final JdbcTemplate jdbc;
    public SchoolRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<SchoolDto> SCHOOL = (rs, i) -> new SchoolDto(
        UUID.fromString(rs.getString("id")),
        rs.getString("slug"),
        rs.getString("name"),
        rs.getString("board_code"),
        rs.getString("gstin"),
        rs.getString("state_code"),
        rs.getBoolean("is_active")
    );

    public List<SchoolDto> list() {
        return jdbc.query(
            "SELECT id, slug, name, board_code, gstin, state_code, is_active FROM school ORDER BY name",
            SCHOOL
        );
    }

    public Optional<SchoolDto> find(UUID id) {
        var rows = jdbc.query(
            "SELECT id, slug, name, board_code, gstin, state_code, is_active FROM school WHERE id = ?",
            SCHOOL, id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public SchoolDto create(String slug, String name, String boardCode, String gstin, String stateCode) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO school (id, slug, name, board_code, gstin, state_code) VALUES (?, ?, ?, ?, ?, ?)",
            id, slug, name, boardCode, gstin, stateCode
        );
        return find(id).orElseThrow();
    }

    public List<AcademicYearDto> listAcademicYears(UUID schoolId) {
        return jdbc.query(
            "SELECT id, code, starts_on, ends_on, is_current FROM academic_year WHERE school_id = ? ORDER BY starts_on DESC",
            (rs, i) -> new AcademicYearDto(
                UUID.fromString(rs.getString("id")),
                rs.getString("code"),
                rs.getDate("starts_on").toLocalDate(),
                rs.getDate("ends_on").toLocalDate(),
                rs.getBoolean("is_current")
            ),
            schoolId
        );
    }

    public List<GradeDto> listGrades(UUID schoolId) {
        return jdbc.query(
            "SELECT id, code, name, sort_order FROM grade WHERE school_id = ? ORDER BY sort_order",
            (rs, i) -> new GradeDto(
                UUID.fromString(rs.getString("id")),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("sort_order")
            ),
            schoolId
        );
    }

    public List<SectionDto> listSections(UUID schoolId, UUID academicYearId) {
        String sql =
            "SELECT s.id, s.school_id, s.grade_id, g.name AS grade_name, s.academic_year_id, " +
            "       s.code, s.name, s.curriculum_id, s.strategy_code, s.capacity " +
            "FROM section s JOIN grade g ON g.id = s.grade_id " +
            "WHERE s.school_id = ?" +
            (academicYearId == null ? "" : " AND s.academic_year_id = ?") +
            " ORDER BY g.sort_order, s.code";
        RowMapper<SectionDto> mapper = (rs, i) -> new SectionDto(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("school_id")),
            UUID.fromString(rs.getString("grade_id")),
            rs.getString("grade_name"),
            UUID.fromString(rs.getString("academic_year_id")),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("curriculum_id") == null ? null : UUID.fromString(rs.getString("curriculum_id")),
            rs.getString("strategy_code"),
            (Integer) rs.getObject("capacity")
        );
        return academicYearId == null
            ? jdbc.query(sql, mapper, schoolId)
            : jdbc.query(sql, mapper, schoolId, academicYearId);
    }
}
