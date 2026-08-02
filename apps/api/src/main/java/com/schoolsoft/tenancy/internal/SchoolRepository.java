package com.schoolsoft.tenancy.internal;

import com.schoolsoft.tenancy.api.AcademicYearDto;
import com.schoolsoft.tenancy.api.CampusDto;
import com.schoolsoft.tenancy.api.GradeDto;
import com.schoolsoft.tenancy.api.SchoolDto;
import com.schoolsoft.tenancy.api.SectionDto;
import com.schoolsoft.tenancy.api.SectionSubjectTeacherDto;
import com.schoolsoft.tenancy.api.SubjectDto;
import com.schoolsoft.tenancy.api.TermDto;
import java.sql.Date;
import java.time.LocalDate;
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

    // -------------------------- Campus --------------------------

    private static final RowMapper<CampusDto> CAMPUS = (rs, i) -> new CampusDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("name"),
        rs.getBoolean("is_primary")
    );

    public List<CampusDto> listCampuses(UUID schoolId) {
        return jdbc.query(
            "SELECT id, school_id, name, is_primary FROM campus WHERE school_id = ? ORDER BY is_primary DESC, name",
            CAMPUS, schoolId
        );
    }

    public CampusDto createCampus(UUID schoolId, String name, boolean isPrimary) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO campus (id, school_id, name, is_primary) VALUES (?, ?, ?, ?)",
            id, schoolId, name, isPrimary
        );
        return jdbc.queryForObject(
            "SELECT id, school_id, name, is_primary FROM campus WHERE id = ?", CAMPUS, id
        );
    }

    // -------------------------- Academic Year --------------------------

    public AcademicYearDto createAcademicYear(UUID schoolId, String code, LocalDate startsOn, LocalDate endsOn, boolean isCurrent) {
        UUID id = UUID.randomUUID();
        if (isCurrent) {
            jdbc.update("UPDATE academic_year SET is_current = FALSE WHERE school_id = ?", schoolId);
        }
        jdbc.update(
            "INSERT INTO academic_year (id, school_id, code, starts_on, ends_on, is_current) VALUES (?, ?, ?, ?, ?, ?)",
            id, schoolId, code, Date.valueOf(startsOn), Date.valueOf(endsOn), isCurrent
        );
        return jdbc.queryForObject(
            "SELECT id, code, starts_on, ends_on, is_current FROM academic_year WHERE id = ?",
            (rs, i) -> new AcademicYearDto(
                UUID.fromString(rs.getString("id")),
                rs.getString("code"),
                rs.getDate("starts_on").toLocalDate(),
                rs.getDate("ends_on").toLocalDate(),
                rs.getBoolean("is_current")
            ),
            id
        );
    }

    // -------------------------- Term --------------------------

    private static final RowMapper<TermDto> TERM = (rs, i) -> new TermDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("academic_year_id")),
        rs.getString("code"),
        rs.getString("name"),
        rs.getDate("starts_on").toLocalDate(),
        rs.getDate("ends_on").toLocalDate()
    );

    public List<TermDto> listTerms(UUID academicYearId) {
        return jdbc.query(
            "SELECT id, academic_year_id, code, name, starts_on, ends_on FROM term " +
            "WHERE academic_year_id = ? ORDER BY starts_on",
            TERM, academicYearId
        );
    }

    public TermDto createTerm(UUID academicYearId, String code, String name, LocalDate startsOn, LocalDate endsOn) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO term (id, academic_year_id, code, name, starts_on, ends_on) VALUES (?, ?, ?, ?, ?, ?)",
            id, academicYearId, code, name, Date.valueOf(startsOn), Date.valueOf(endsOn)
        );
        return jdbc.queryForObject(
            "SELECT id, academic_year_id, code, name, starts_on, ends_on FROM term WHERE id = ?", TERM, id
        );
    }

    // -------------------------- Grade --------------------------

    public GradeDto createGrade(UUID schoolId, String code, String name, int sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO grade (id, school_id, code, name, sort_order) VALUES (?, ?, ?, ?, ?)",
            id, schoolId, code, name, sortOrder
        );
        return jdbc.queryForObject(
            "SELECT id, code, name, sort_order FROM grade WHERE id = ?",
            (rs, i) -> new GradeDto(
                UUID.fromString(rs.getString("id")), rs.getString("code"), rs.getString("name"), rs.getInt("sort_order")
            ),
            id
        );
    }

    // -------------------------- Section --------------------------

    public SectionDto createSection(
        UUID schoolId, UUID gradeId, UUID academicYearId, String code, String name, String strategyCode, Integer capacity
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO section (id, school_id, grade_id, academic_year_id, code, name, strategy_code, capacity) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, gradeId, academicYearId, code, name, strategyCode, capacity
        );
        return findSection(id).orElseThrow();
    }

    public Optional<SectionDto> findSection(UUID id) {
        var rows = jdbc.query(
            "SELECT s.id, s.school_id, s.grade_id, g.name AS grade_name, s.academic_year_id, " +
            "       s.code, s.name, s.curriculum_id, s.strategy_code, s.capacity " +
            "FROM section s JOIN grade g ON g.id = s.grade_id WHERE s.id = ?",
            (rs, i) -> new SectionDto(
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
            ),
            id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void bindSectionCurriculum(UUID sectionId, UUID curriculumId, String strategyCode) {
        jdbc.update(
            "UPDATE section SET curriculum_id = ?, strategy_code = ? WHERE id = ?",
            curriculumId, strategyCode, sectionId
        );
    }

    // -------------------------- Subject --------------------------

    private static final RowMapper<SubjectDto> SUBJECT = (rs, i) -> new SubjectDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("board_code")
    );

    public List<SubjectDto> listSubjects(UUID schoolId) {
        return jdbc.query(
            "SELECT id, school_id, code, name, board_code FROM subject WHERE school_id = ? ORDER BY name",
            SUBJECT, schoolId
        );
    }

    public SubjectDto createSubject(UUID schoolId, String code, String name, String boardCode) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO subject (id, school_id, code, name, board_code) VALUES (?, ?, ?, ?, ?)",
            id, schoolId, code, name, boardCode
        );
        return jdbc.queryForObject(
            "SELECT id, school_id, code, name, board_code FROM subject WHERE id = ?", SUBJECT, id
        );
    }

    // -------------------------- Section-Subject-Teacher --------------------------

    private static final RowMapper<SectionSubjectTeacherDto> SST = (rs, i) -> new SectionSubjectTeacherDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("section_id")),
        UUID.fromString(rs.getString("subject_id")),
        rs.getString("subject_name"),
        UUID.fromString(rs.getString("teacher_staff_id")),
        rs.getString("teacher_name"),
        rs.getBoolean("is_primary")
    );

    public List<SectionSubjectTeacherDto> listSectionSubjectTeachers(UUID sectionId) {
        return jdbc.query(
            "SELECT sst.id, sst.section_id, sst.subject_id, sub.name AS subject_name, " +
            "       sst.teacher_staff_id, (st.first_name || ' ' || COALESCE(st.last_name, '')) AS teacher_name, " +
            "       sst.is_primary " +
            "FROM section_subject_teacher sst " +
            "JOIN subject sub ON sub.id = sst.subject_id " +
            "JOIN staff st ON st.id = sst.teacher_staff_id " +
            "WHERE sst.section_id = ? ORDER BY sub.name",
            SST, sectionId
        );
    }

    public SectionSubjectTeacherDto assignSectionSubjectTeacher(
        UUID sectionId, UUID subjectId, UUID teacherStaffId, boolean isPrimary
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO section_subject_teacher (id, section_id, subject_id, teacher_staff_id, is_primary) " +
            "VALUES (?, ?, ?, ?, ?)",
            id, sectionId, subjectId, teacherStaffId, isPrimary
        );
        return jdbc.queryForObject(
            "SELECT sst.id, sst.section_id, sst.subject_id, sub.name AS subject_name, " +
            "       sst.teacher_staff_id, (st.first_name || ' ' || COALESCE(st.last_name, '')) AS teacher_name, " +
            "       sst.is_primary " +
            "FROM section_subject_teacher sst " +
            "JOIN subject sub ON sub.id = sst.subject_id " +
            "JOIN staff st ON st.id = sst.teacher_staff_id " +
            "WHERE sst.id = ?",
            SST, id
        );
    }
}
