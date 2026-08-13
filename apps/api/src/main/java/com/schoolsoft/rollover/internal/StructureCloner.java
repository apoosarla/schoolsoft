package com.schoolsoft.rollover.internal;

import com.schoolsoft.tenancy.api.AcademicYearDto;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds next year's shell (YEC-02): the sections children will be allocated
 * into, and the fee structures those children will be billed from.
 *
 * Cloned rather than moved, and cloned into a year that is still
 * {@code planning}, so the school can rename a section, change a capacity or
 * re-price a grade before anybody is in it — and so this year's rows keep
 * saying what they said.
 *
 * Two things deliberately do not come across:
 *
 * <ul>
 *   <li><b>Teacher assignments.</b> Who teaches 6A next year is a decision, not
 *       a continuation (YEC-10). Carrying them silently is how a teacher
 *       discovers in April that they still hold a class they asked to leave.</li>
 *   <li><b>Bell schedules.</b> They hang off the grade, not the year, so they
 *       already apply to next year's sections. Cloning them would produce two
 *       schedules competing for one grade.</li>
 * </ul>
 */
@Service
public class StructureCloner {

    private final JdbcTemplate jdbc;

    public StructureCloner(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Result(int sectionsCloned, int sectionsAlreadyThere, int feeStructuresCloned) {}

    /**
     * Idempotent on {@code section.source_section_id}: a second call adds
     * nothing, which matters because the wizard's "clone" button is exactly the
     * kind of thing that gets pressed twice.
     */
    @Transactional
    public Result clone(UUID schoolId, AcademicYearDto from, AcademicYearDto to) {
        var sources = jdbc.query(
            "SELECT s.id, s.grade_id, s.code, s.name, s.curriculum_id, s.strategy_code, s.capacity, " +
            "       s.campus_id " +
            "FROM section s WHERE s.school_id = ? AND s.academic_year_id = ? ORDER BY s.code",
            (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", UUID.fromString(rs.getString("id")));
                row.put("gradeId", UUID.fromString(rs.getString("grade_id")));
                row.put("code", rs.getString("code"));
                row.put("name", rs.getString("name"));
                row.put("curriculumId", rs.getString("curriculum_id") == null ? null
                    : UUID.fromString(rs.getString("curriculum_id")));
                row.put("strategyCode", rs.getString("strategy_code"));
                row.put("capacity", rs.getObject("capacity"));
                row.put("campusId", rs.getString("campus_id") == null ? null
                    : UUID.fromString(rs.getString("campus_id")));
                return row;
            },
            schoolId, from.id());

        int cloned = 0;
        int existing = 0;
        for (Map<String, Object> source : sources) {
            Integer already = jdbc.queryForObject(
                "SELECT count(*) FROM section WHERE academic_year_id = ? AND grade_id = ? AND code = ?",
                Integer.class, to.id(), source.get("gradeId"), source.get("code"));
            if (already != null && already > 0) {
                // Same grade and code already exist next year — either a second
                // clone or a section the school made by hand. Point it back at
                // its source so allocation can still follow 5A into 6A.
                jdbc.update(
                    "UPDATE section SET source_section_id = ? " +
                    "WHERE academic_year_id = ? AND grade_id = ? AND code = ? AND source_section_id IS NULL",
                    source.get("id"), to.id(), source.get("gradeId"), source.get("code"));
                existing++;
                continue;
            }
            jdbc.update(
                "INSERT INTO section (id, school_id, grade_id, academic_year_id, code, name, curriculum_id, " +
                "  strategy_code, capacity, campus_id, source_section_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), schoolId, source.get("gradeId"), to.id(), source.get("code"),
                renameYear(String.valueOf(source.get("name")), from.code(), to.code()),
                source.get("curriculumId"), source.get("strategyCode"), source.get("capacity"),
                source.get("campusId"), source.get("id"));
            cloned++;
        }

        int structures = cloneFeeStructures(schoolId, from.id(), to.id());
        return new Result(cloned, existing, structures);
    }

    /**
     * Next year's fee structure is a copy of this year's, at this year's
     * prices: the school edits it before the year starts. Skips any grade
     * already carrying a structure next year, so re-running adds nothing.
     */
    private int cloneFeeStructures(UUID schoolId, UUID fromAy, UUID toAy) {
        var sources = jdbc.query(
            "SELECT fs.id, fs.grade_id, fs.name FROM fee_structure fs " +
            "WHERE fs.school_id = ? AND fs.academic_year_id = ? " +
            "  AND NOT EXISTS (SELECT 1 FROM fee_structure t " +
            "                  WHERE t.school_id = fs.school_id AND t.academic_year_id = ? " +
            "                    AND t.grade_id = fs.grade_id)",
            (rs, i) -> new Object[]{
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("grade_id")),
                rs.getString("name")
            },
            schoolId, fromAy, toAy);

        int cloned = 0;
        for (Object[] source : sources) {
            UUID newId = UUID.randomUUID();
            jdbc.update(
                "INSERT INTO fee_structure (id, school_id, grade_id, academic_year_id, name, schedule) " +
                "SELECT ?, school_id, grade_id, ?, ?, schedule FROM fee_structure WHERE id = ?",
                newId, toAy, source[2], source[0]);
            jdbc.update(
                "INSERT INTO fee_structure_line (id, fee_structure_id, fee_head_id, amount) " +
                "SELECT gen_random_uuid(), ?, fee_head_id, amount FROM fee_structure_line " +
                "WHERE fee_structure_id = ?",
                newId, source[0]);
            cloned++;
        }
        return cloned;
    }

    /** "Grade 5-A 2026-27" → "Grade 5-A 2027-28"; names without the year are left alone. */
    private String renameYear(String name, String fromCode, String toCode) {
        return name == null ? null : name.replace(fromCode, toCode);
    }
}
