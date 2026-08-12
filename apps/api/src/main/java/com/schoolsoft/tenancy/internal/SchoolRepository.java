package com.schoolsoft.tenancy.internal;

import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.AcademicYearDto;
import com.schoolsoft.tenancy.api.CampusDto;
import com.schoolsoft.tenancy.api.ElectiveGroupDto;
import com.schoolsoft.tenancy.api.GradeDto;
import com.schoolsoft.tenancy.api.SchoolDto;
import com.schoolsoft.tenancy.api.SectionDto;
import com.schoolsoft.tenancy.api.SectionSubjectTeacherDto;
import com.schoolsoft.tenancy.api.SubjectDto;
import com.schoolsoft.tenancy.api.TermDto;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SchoolRepository {

    private final JdbcTemplate jdbc;
    private final Authz authz;

    public SchoolRepository(JdbcTemplate jdbc, Authz authz) {
        this.jdbc = jdbc;
        this.authz = authz;
    }

    /**
     * Appends a campus restriction when the caller holds only campus-scoped
     * role grants. School-wide staff and chain admins get an empty scope and
     * are left unfiltered (GAP-24).
     */
    private String campusFilter(String columnExpression, List<Object> args) {
        List<UUID> scope = authz.campusScopeOfCurrentUser();
        if (scope.isEmpty()) return "";
        args.addAll(scope);
        return " AND " + columnExpression + " IN (" +
            String.join(",", scope.stream().map(x -> "?").toList()) + ")";
    }

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

    private static final String AY_COLS =
        "id, code, starts_on, ends_on, is_current, status, closed_at, reopened_at, reopen_reason";

    private static final RowMapper<AcademicYearDto> ACADEMIC_YEAR = (rs, i) -> new AcademicYearDto(
        UUID.fromString(rs.getString("id")),
        rs.getString("code"),
        rs.getDate("starts_on").toLocalDate(),
        rs.getDate("ends_on").toLocalDate(),
        rs.getBoolean("is_current"),
        rs.getString("status"),
        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
        rs.getTimestamp("reopened_at") == null ? null : rs.getTimestamp("reopened_at").toInstant(),
        rs.getString("reopen_reason")
    );

    public List<AcademicYearDto> listAcademicYears(UUID schoolId) {
        return jdbc.query(
            "SELECT " + AY_COLS + " FROM academic_year WHERE school_id = ? ORDER BY starts_on DESC",
            ACADEMIC_YEAR, schoolId
        );
    }

    public AcademicYearDto findAcademicYear(UUID id) {
        var rows = jdbc.query("SELECT " + AY_COLS + " FROM academic_year WHERE id = ?", ACADEMIC_YEAR, id);
        if (rows.isEmpty()) throw new NotFoundException("Academic year not found: " + id);
        return rows.get(0);
    }

    /**
     * Moves a year through planning → active → closed, or back via an explicit
     * reopen. Closing clears {@code is_current} (the schema forbids a closed
     * year being current); reopening does not restore it, because which year is
     * current is a separate decision from whether an old one may be edited.
     */
    public AcademicYearDto setAcademicYearStatus(UUID id, String status, UUID actingStaffId, String reason) {
        AcademicYearDto current = findAcademicYear(id);
        switch (status) {
            case "closed" -> {
                if ("closed".equals(current.status())) return current;
                jdbc.update(
                    "UPDATE academic_year SET status = 'closed', is_current = FALSE, closed_at = now(), " +
                    "  closed_by_staff_id = ? WHERE id = ?", actingStaffId, id);
            }
            case "active" -> {
                boolean reopening = "closed".equals(current.status());
                if (reopening && (reason == null || reason.isBlank())) {
                    throw new IllegalArgumentException("Reopening a closed academic year requires a reason");
                }
                jdbc.update(
                    "UPDATE academic_year SET status = 'active', " +
                    "  reopened_at = CASE WHEN ? THEN now() ELSE reopened_at END, " +
                    "  reopened_by_staff_id = CASE WHEN ? THEN ? ELSE reopened_by_staff_id END, " +
                    "  reopen_reason = CASE WHEN ? THEN ? ELSE reopen_reason END " +
                    "WHERE id = ?",
                    reopening, reopening, actingStaffId, reopening, reason, id);
            }
            case "planning" -> {
                if ("closed".equals(current.status())) {
                    throw new IllegalArgumentException(
                        "A closed academic year cannot return to planning; reopen it instead");
                }
                jdbc.update("UPDATE academic_year SET status = 'planning' WHERE id = ?", id);
            }
            default -> throw new IllegalArgumentException(
                "Unknown academic year status: " + status + " (planning | active | closed)");
        }
        return findAcademicYear(id);
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
        List<Object> args = new ArrayList<>();
        args.add(schoolId);
        StringBuilder sql = new StringBuilder(
            "SELECT s.id, s.school_id, s.grade_id, g.name AS grade_name, s.academic_year_id, " +
            "       s.code, s.name, s.curriculum_id, s.strategy_code, s.capacity, s.campus_id " +
            "FROM section s JOIN grade g ON g.id = s.grade_id WHERE s.school_id = ?");
        if (academicYearId != null) {
            sql.append(" AND s.academic_year_id = ?");
            args.add(academicYearId);
        }
        sql.append(campusFilter("s.campus_id", args));
        sql.append(" ORDER BY g.sort_order, s.code");
        return jdbc.query(sql.toString(), SECTION, args.toArray());
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
        if (!endsOn.isAfter(startsOn)) {
            throw new IllegalArgumentException(
                "Academic year " + code + " ends on or before it starts (" + startsOn + " .. " + endsOn + ")");
        }
        // The exclusion constraint in V016 is the real guarantee; this check exists
        // so the caller is told which year it collided with rather than reading a
        // constraint name out of a 500.
        List<String> overlapping = jdbc.queryForList(
            "SELECT code || ' (' || starts_on || ' .. ' || ends_on || ')' FROM academic_year " +
            "WHERE school_id = ? AND daterange(starts_on, ends_on, '[]') && daterange(?, ?, '[]')",
            String.class, schoolId, Date.valueOf(startsOn), Date.valueOf(endsOn));
        if (!overlapping.isEmpty()) {
            throw new IllegalArgumentException(
                "Academic year " + code + " (" + startsOn + " .. " + endsOn + ") overlaps " +
                String.join(", ", overlapping));
        }

        UUID id = UUID.randomUUID();
        if (isCurrent) {
            jdbc.update("UPDATE academic_year SET is_current = FALSE WHERE school_id = ?", schoolId);
        }
        jdbc.update(
            "INSERT INTO academic_year (id, school_id, code, starts_on, ends_on, is_current) VALUES (?, ?, ?, ?, ?, ?)",
            id, schoolId, code, Date.valueOf(startsOn), Date.valueOf(endsOn), isCurrent
        );
        return findAcademicYear(id);
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
        AcademicYearDto year = findAcademicYear(academicYearId);
        if (endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("Term " + code + " ends before it starts");
        }
        if (startsOn.isBefore(year.startsOn()) || endsOn.isAfter(year.endsOn())) {
            throw new IllegalArgumentException(
                "Term " + code + " (" + startsOn + " .. " + endsOn + ") falls outside academic year " +
                year.code() + " (" + year.startsOn() + " .. " + year.endsOn() + ")");
        }
        List<String> overlapping = jdbc.queryForList(
            "SELECT code || ' (' || starts_on || ' .. ' || ends_on || ')' FROM term " +
            "WHERE academic_year_id = ? AND daterange(starts_on, ends_on, '[]') && daterange(?, ?, '[]')",
            String.class, academicYearId, Date.valueOf(startsOn), Date.valueOf(endsOn));
        if (!overlapping.isEmpty()) {
            throw new IllegalArgumentException(
                "Term " + code + " (" + startsOn + " .. " + endsOn + ") overlaps " +
                String.join(", ", overlapping));
        }

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
        UUID schoolId, UUID gradeId, UUID academicYearId, String code, String name, String strategyCode,
        Integer capacity, UUID campusId
    ) {
        UUID resolvedCampus = campusId == null ? primaryCampusOf(schoolId) : campusId;
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO section (id, school_id, grade_id, academic_year_id, code, name, strategy_code, " +
            "  capacity, campus_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, gradeId, academicYearId, code, name, strategyCode, capacity, resolvedCampus
        );
        return findSection(id).orElseThrow();
    }

    /** Single-campus schools never pick one, so the primary stands in. */
    public UUID primaryCampusOf(UUID schoolId) {
        var rows = jdbc.query(
            "SELECT id FROM campus WHERE school_id = ? ORDER BY is_primary DESC, name LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString("id")), schoolId);
        if (rows.isEmpty()) throw new NotFoundException("School has no campus: " + schoolId);
        return rows.get(0);
    }

    private static final RowMapper<SectionDto> SECTION = (rs, i) -> new SectionDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("grade_id")),
        rs.getString("grade_name"),
        UUID.fromString(rs.getString("academic_year_id")),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("curriculum_id") == null ? null : UUID.fromString(rs.getString("curriculum_id")),
        rs.getString("strategy_code"),
        (Integer) rs.getObject("capacity"),
        rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id"))
    );

    public Optional<SectionDto> findSection(UUID id) {
        var rows = jdbc.query(
            "SELECT s.id, s.school_id, s.grade_id, g.name AS grade_name, s.academic_year_id, " +
            "       s.code, s.name, s.curriculum_id, s.strategy_code, s.capacity, s.campus_id " +
            "FROM section s JOIN grade g ON g.id = s.grade_id WHERE s.id = ?",
            SECTION, id
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
        rs.getBoolean("is_primary"),
        rs.getBoolean("is_elective")
    );

    public List<SectionSubjectTeacherDto> listSectionSubjectTeachers(UUID sectionId) {
        return jdbc.query(
            "SELECT sst.id, sst.section_id, sst.subject_id, sub.name AS subject_name, " +
            "       sst.teacher_staff_id, (st.first_name || ' ' || COALESCE(st.last_name, '')) AS teacher_name, " +
            "       sst.is_primary, sst.is_elective " +
            "FROM section_subject_teacher sst " +
            "JOIN subject sub ON sub.id = sst.subject_id " +
            "JOIN staff st ON st.id = sst.teacher_staff_id " +
            "WHERE sst.section_id = ? ORDER BY sub.name",
            SST, sectionId
        );
    }

    public SectionSubjectTeacherDto assignSectionSubjectTeacher(
        UUID sectionId, UUID subjectId, UUID teacherStaffId, boolean isPrimary, boolean isElective
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO section_subject_teacher (id, section_id, subject_id, teacher_staff_id, is_primary, " +
            "  is_elective) VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (section_id, subject_id, teacher_staff_id) DO UPDATE SET " +
            "  is_primary = EXCLUDED.is_primary, is_elective = EXCLUDED.is_elective",
            id, sectionId, subjectId, teacherStaffId, isPrimary, isElective
        );
        id = jdbc.queryForObject(
            "SELECT id FROM section_subject_teacher WHERE section_id = ? AND subject_id = ? " +
            "  AND teacher_staff_id = ?", UUID.class, sectionId, subjectId, teacherStaffId);
        return jdbc.queryForObject(
            "SELECT sst.id, sst.section_id, sst.subject_id, sub.name AS subject_name, " +
            "       sst.teacher_staff_id, (st.first_name || ' ' || COALESCE(st.last_name, '')) AS teacher_name, " +
            "       sst.is_primary, sst.is_elective " +
            "FROM section_subject_teacher sst " +
            "JOIN subject sub ON sub.id = sst.subject_id " +
            "JOIN staff st ON st.id = sst.teacher_staff_id " +
            "WHERE sst.id = ?",
            SST, id
        );
    }
    // -------------------------- Elective groups --------------------------

    /**
     * Option blocks a grade offers in a year, each with its subjects. Read by
     * the admin UI when a student's options are entered, and by
     * {@code SubjectSetResolver}'s callers to show what is still unchosen.
     */
    public List<ElectiveGroupDto> listElectiveGroups(UUID schoolId, UUID academicYearId, UUID gradeId) {
        StringBuilder sql = new StringBuilder(
            "SELECT id, school_id, academic_year_id, grade_id, code, name, min_picks, max_picks " +
            "FROM elective_group WHERE school_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(schoolId);
        if (academicYearId != null) { sql.append(" AND academic_year_id = ?"); args.add(academicYearId); }
        if (gradeId != null) { sql.append(" AND grade_id = ?"); args.add(gradeId); }
        sql.append(" ORDER BY code");

        return jdbc.query(sql.toString(), (rs, i) -> {
            UUID id = UUID.fromString(rs.getString("id"));
            return new ElectiveGroupDto(
                id,
                UUID.fromString(rs.getString("school_id")),
                UUID.fromString(rs.getString("academic_year_id")),
                UUID.fromString(rs.getString("grade_id")),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("min_picks"),
                rs.getInt("max_picks"),
                listElectiveOptions(id)
            );
        }, args.toArray());
    }

    private List<ElectiveGroupDto.Option> listElectiveOptions(UUID electiveGroupId) {
        return jdbc.query(
            "SELECT o.subject_id, s.code, s.name, o.capacity FROM elective_group_option o " +
            "JOIN subject s ON s.id = o.subject_id WHERE o.elective_group_id = ? ORDER BY s.code",
            (rs, i) -> new ElectiveGroupDto.Option(
                UUID.fromString(rs.getString("subject_id")), rs.getString("code"), rs.getString("name"),
                (Integer) rs.getObject("capacity")),
            electiveGroupId);
    }

    public ElectiveGroupDto createElectiveGroup(
        UUID schoolId, UUID academicYearId, UUID gradeId, String code, String name,
        int minPicks, int maxPicks, List<UUID> subjectIds
    ) {
        if (maxPicks < minPicks) {
            throw new IllegalArgumentException("maxPicks must be at least minPicks");
        }
        if (subjectIds.size() < maxPicks) {
            throw new IllegalArgumentException(
                "Elective group " + code + " offers " + subjectIds.size() + " subject(s) but allows "
                + maxPicks + " pick(s)");
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO elective_group (id, school_id, academic_year_id, grade_id, code, name, " +
            "  min_picks, max_picks) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, academicYearId, gradeId, code, name, minPicks, maxPicks);
        for (UUID subjectId : subjectIds) {
            jdbc.update(
                "INSERT INTO elective_group_option (elective_group_id, subject_id) VALUES (?, ?) " +
                "ON CONFLICT DO NOTHING", id, subjectId);
        }
        return listElectiveGroups(schoolId, academicYearId, gradeId).stream()
            .filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }
}
