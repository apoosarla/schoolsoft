package com.schoolsoft.people.internal;

import com.schoolsoft.iam.api.CampusScope;
import com.schoolsoft.platform.time.SchoolClock;
import com.schoolsoft.iam.api.DirectoryScope;
import com.schoolsoft.people.api.GuardianDto;
import com.schoolsoft.people.api.PeopleController;
import com.schoolsoft.people.api.StaffDto;
import com.schoolsoft.people.api.StaffOnboarding;
import com.schoolsoft.people.api.StudentDto;
import com.schoolsoft.people.api.UserDirectoryEntryDto;
import com.schoolsoft.tenancy.api.NumberSeries;
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
public class PeopleRepository {

    private final JdbcTemplate jdbc;
    private final CampusScope campusScope;
    private final DirectoryScope directoryScope;
    private final SchoolClock clock;
    private final NumberSeries numbers;

    public PeopleRepository(JdbcTemplate jdbc, CampusScope campusScope, DirectoryScope directoryScope,
                            NumberSeries numbers, SchoolClock clock) {
        this.jdbc = jdbc;
        this.campusScope = campusScope;
        this.directoryScope = directoryScope;
        this.numbers = numbers;
        this.clock = clock;
    }

    private static final RowMapper<StudentDto> STUDENT = (rs, i) -> new StudentDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("admission_no"),
        rs.getString("first_name"),
        rs.getString("middle_name"),
        rs.getString("last_name"),
        rs.getDate("dob") == null ? null : rs.getDate("dob").toLocalDate(),
        rs.getString("gender"),
        rs.getString("status"),
        rs.getString("section_id") == null ? null : UUID.fromString(rs.getString("section_id")),
        rs.getString("section_label"),
        rs.getString("roll_no")
    );

    /**
     * "Which class is this child in?", as at today.
     *
     * <p>The active-on-date predicate rather than {@code status = 'active'}: a
     * child whose withdrawal is filed three weeks before their last day is still
     * in 8B, and the directory should say so until the day they actually leave
     * (XFER-03). {@link com.schoolsoft.enrolment.api.EnrolmentActivity} holds the
     * one copy of it.</p>
     */
    private String liveEnrolment() {
        return com.schoolsoft.enrolment.api.EnrolmentActivity
            .activeOnDateLiteral("e", clock.today());
    }

    public List<StudentDto> listStudents(UUID schoolId, UUID sectionId, String q, int limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT s.id, s.school_id, s.admission_no, s.first_name, s.middle_name, s.last_name, " +
            "       s.dob, s.gender, s.status, " +
            "       e.section_id, (g.code || '-' || sec.code) AS section_label, e.roll_no " +
            "FROM student s " +
            "LEFT JOIN enrolment e ON e.student_id = s.id AND " + liveEnrolment() + " " +
            "LEFT JOIN section sec ON sec.id = e.section_id " +
            "LEFT JOIN grade   g   ON g.id = sec.grade_id " +
            "WHERE s.school_id = ? "
        );
        List<Object> args = new ArrayList<>();
        args.add(schoolId);
        if (sectionId != null) { sql.append("AND e.section_id = ? "); args.add(sectionId); }
        if (q != null && !q.isBlank()) {
            sql.append("AND (lower(s.first_name) LIKE ? OR lower(s.last_name) LIKE ? OR s.admission_no = ?) ");
            String like = "%" + q.toLowerCase() + "%";
            args.add(like); args.add(like); args.add(q);
        }
        sql.append("ORDER BY s.first_name LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), STUDENT, args.toArray());
    }

    public Optional<StudentDto> findStudent(UUID id) {
        var rows = jdbc.query(
            "SELECT s.id, s.school_id, s.admission_no, s.first_name, s.middle_name, s.last_name, " +
            "       s.dob, s.gender, s.status, " +
            "       e.section_id, (g.code || '-' || sec.code) AS section_label, e.roll_no " +
            "FROM student s " +
            "LEFT JOIN enrolment e ON e.student_id = s.id AND " + liveEnrolment() + " " +
            "LEFT JOIN section sec ON sec.id = e.section_id " +
            "LEFT JOIN grade   g   ON g.id = sec.grade_id " +
            "WHERE s.id = ?",
            STUDENT, id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Admission number omitted → the school's series issues one (GAP-26). */
    public StudentDto createStudent(PeopleController.CreateStudentRequest req) {
        UUID id = UUID.randomUUID();
        Date dob = req.dob() == null ? null : Date.valueOf(LocalDate.parse(req.dob()));
        String admissionNo = req.admissionNo() == null || req.admissionNo().isBlank()
            ? numbers.next(req.schoolId(), NumberSeries.Kind.admission, null, "ADM{YY}{SEQ:4}", null)
            : req.admissionNo();
        jdbc.update(
            "INSERT INTO student (id, school_id, admission_no, first_name, middle_name, last_name, dob, gender) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, req.schoolId(), admissionNo, req.firstName(), req.middleName(), req.lastName(), dob, req.gender()
        );
        return findStudent(id).orElseThrow();
    }

    /**
     * Puts a member of staff on the books. Reached from outside the school
     * only through {@link com.schoolsoft.people.api.StaffOnboarding}, which is
     * the chain HQ handing a newly opened school its first keyholder — the
     * office's own hiring runs through the school's screens.
     */
    public StaffDto createStaff(StaffOnboarding.NewStaff staff) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO staff (id, school_id, campus_id, employee_no, first_name, last_name, " +
            "                   email, phone, employment_type, joined_on) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, staff.schoolId(), staff.campusId(), staff.employeeNo(), staff.firstName(), staff.lastName(),
            staff.email(), staff.phone(), staff.employmentType(),
            staff.joinedOn() == null ? null : Date.valueOf(staff.joinedOn())
        );
        return findStaff(id).orElseThrow();
    }

    private static RowMapper<StaffDto> staffMapper() {
        return (rs, i) -> new StaffDto(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("school_id")),
            rs.getString("employee_no"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            rs.getString("email"),
            rs.getString("phone"),
            rs.getString("employment_type"),
            rs.getDate("joined_on") == null ? null : rs.getDate("joined_on").toLocalDate(),
            rs.getBoolean("is_active"),
            rs.getString("campus_id") == null ? null : UUID.fromString(rs.getString("campus_id")),
            rs.getDate("left_on") == null ? null : rs.getDate("left_on").toLocalDate(),
            rs.getString("exit_reason"),
            rs.getString("successor_staff_id") == null ? null : UUID.fromString(rs.getString("successor_staff_id")),
            rs.getInt("version")
        );
    }

    private static final String STAFF_SELECT =
        "SELECT id, school_id, employee_no, first_name, last_name, email, phone, employment_type, joined_on, " +
        "       is_active, campus_id, left_on, exit_reason, successor_staff_id, version FROM staff ";

    public Optional<UUID> primaryCampusOf(UUID schoolId) {
        return jdbc.query(
            "SELECT id FROM campus WHERE school_id = ? ORDER BY is_primary DESC, name LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString(1)), schoolId).stream().findFirst();
    }

    public boolean employeeNoTaken(UUID schoolId, String employeeNo) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM staff WHERE school_id = ? AND employee_no = ?", Integer.class, schoolId, employeeNo);
        return n != null && n > 0;
    }

    /** False when somebody saved first — the caller turns that into a 409. */
    public boolean updateStaff(UUID id, String firstName, String lastName, String email, String phone,
                               String employmentType, LocalDate joinedOn, UUID campusId, int expectedVersion) {
        return jdbc.update(
            "UPDATE staff SET first_name = ?, last_name = ?, email = ?, phone = ?, employment_type = ?, " +
            "  joined_on = ?, campus_id = ?, version = version + 1 " +
            "WHERE id = ? AND version = ?",
            firstName, lastName, email, phone, employmentType,
            joinedOn == null ? null : Date.valueOf(joinedOn), campusId, id, expectedVersion) == 1;
    }

    /**
     * Records the exit. One conditional UPDATE naming the state it moves out
     * of — still on the books — so two people filing the same exit cannot both
     * succeed with different dates.
     */
    public boolean recordStaffExit(UUID id, LocalDate lastWorkingDate, String reason, UUID successorStaffId,
                                   int expectedVersion) {
        return jdbc.update(
            "UPDATE staff SET left_on = ?, exit_reason = ?, successor_staff_id = ?, exit_recorded_at = now(), " +
            "  version = version + 1 " +
            "WHERE id = ? AND left_on IS NULL AND version = ?",
            Date.valueOf(lastWorkingDate), reason, successorStaffId, id, expectedVersion) == 1;
    }

    public Optional<StaffDto> findStaff(UUID id) {
        return jdbc.query(
            STAFF_SELECT + "WHERE id = ?",
            staffMapper(), id).stream().findFirst();
    }

    public List<StudentDto> studentsOfGuardian(UUID guardianId) {
        return jdbc.query(
            "SELECT s.id, s.school_id, s.admission_no, s.first_name, s.middle_name, s.last_name, " +
            "       s.dob, s.gender, s.status, " +
            "       e.section_id, (g.code || '-' || sec.code) AS section_label, e.roll_no " +
            "FROM student s " +
            "JOIN guardian_student gs ON gs.student_id = s.id " +
            "LEFT JOIN enrolment e ON e.student_id = s.id AND " + liveEnrolment() + " " +
            "LEFT JOIN section sec ON sec.id = e.section_id " +
            "LEFT JOIN grade   g   ON g.id = sec.grade_id " +
            "WHERE gs.guardian_id = ? " +
            "ORDER BY s.first_name",
            STUDENT, guardianId
        );
    }

    public List<GuardianDto> guardiansOfStudent(UUID studentId) {
        return jdbc.query(
            "SELECT g.id, g.school_id, g.first_name, g.last_name, g.phone, g.email, " +
            "       g.opt_in_whatsapp, g.opt_in_push, g.opt_in_email " +
            "FROM guardian g JOIN guardian_student gs ON gs.guardian_id = g.id " +
            "WHERE gs.student_id = ?",
            (rs, i) -> new GuardianDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("school_id")),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getBoolean("opt_in_whatsapp"),
                rs.getBoolean("opt_in_push"),
                rs.getBoolean("opt_in_email")
            ),
            studentId
        );
    }

    public List<GuardianDto> listGuardians(UUID schoolId, String q) {
        String sql =
            "SELECT id, school_id, first_name, last_name, phone, email, " +
            "       opt_in_whatsapp, opt_in_push, opt_in_email " +
            "FROM guardian WHERE school_id = ?" +
            (q == null || q.isBlank() ? "" : " AND (phone = ? OR email ILIKE ? OR first_name ILIKE ?)") +
            " ORDER BY first_name";
        if (q == null || q.isBlank()) {
            return jdbc.query(sql, guardianMapper(), schoolId);
        }
        String like = "%" + q + "%";
        return jdbc.query(sql, guardianMapper(), schoolId, q, like, like);
    }

    /**
     * {@code onBooksOn} narrows the list to the people still working at the
     * school that day — what a picker wants. Null lists everybody who ever
     * did, which is what the staff register wants.
     */
    public List<StaffDto> listStaff(UUID schoolId, String q, LocalDate onBooksOn) {
        List<Object> args = new java.util.ArrayList<>();
        args.add(schoolId);
        StringBuilder sql = new StringBuilder(STAFF_SELECT + "WHERE school_id = ?");
        if (onBooksOn != null) {
            sql.append(" AND (left_on IS NULL OR left_on >= ?)");
            args.add(Date.valueOf(onBooksOn));
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (email ILIKE ? OR first_name ILIKE ? OR employee_no = ?)");
            args.add("%" + q + "%");
            args.add("%" + q + "%");
            args.add(q);
        }
        // A campus-level admin sees their campus's staff and no one else's (GAP-24).
        List<UUID> scope = campusScope.ofCurrentUser();
        if (!scope.isEmpty()) {
            sql.append(" AND campus_id IN (")
               .append(String.join(",", scope.stream().map(x -> "?").toList()))
               .append(")");
            args.addAll(scope);
        }
        sql.append(" ORDER BY first_name");
        return jdbc.query(sql.toString(), staffMapper(), args.toArray());
    }

    /**
     * Resolves {@code user_account} rows to a display name by joining the
     * table its {@code subject_type} points at (staff | guardian | student —
     * {@code chain_admin} accounts are school-less and excluded). Backs
     * participant pickers (e.g. comms thread creation) that otherwise have
     * no way to turn a login identity into a human name.
     */
    public List<UserDirectoryEntryDto> listDirectory(UUID schoolId, String q, String subjectType) {
        StringBuilder sql = new StringBuilder(
            "SELECT ua.id AS user_account_id, ua.subject_type, ua.subject_id, ua.email, ua.phone, " +
            "       COALESCE(st.first_name, g.first_name, stu.first_name) AS first_name, " +
            "       COALESCE(st.last_name, g.last_name, stu.last_name) AS last_name " +
            "FROM user_account ua " +
            "LEFT JOIN staff    st  ON ua.subject_type = 'staff'    AND st.id  = ua.subject_id " +
            "LEFT JOIN guardian g   ON ua.subject_type = 'guardian' AND g.id   = ua.subject_id " +
            "LEFT JOIN student  stu ON ua.subject_type = 'student'  AND stu.id = ua.subject_id " +
            "WHERE ua.school_id = ? AND ua.is_active AND ua.subject_type != 'chain_admin' "
        );
        List<Object> args = new ArrayList<>();
        args.add(schoolId);

        // A family sees staff, and only the staff they have a reason to
        // contact (GAP-31). Narrowed here rather than at the controller
        // because a directory read that forgets to narrow itself hands one
        // parent every other family's phone number.
        var visible = directoryScope.ofCurrentUser();
        if (!visible.unrestricted()) {
            if (visible.staffIds().isEmpty()) return List.of();
            sql.append("AND ua.subject_type = 'staff' AND ua.subject_id IN (")
               .append(String.join(",", java.util.Collections.nCopies(visible.staffIds().size(), "?")))
               .append(") ");
            args.addAll(visible.staffIds());
        }

        if (subjectType != null && !subjectType.isBlank()) {
            sql.append("AND ua.subject_type = ? ");
            args.add(subjectType);
        }
        if (q != null && !q.isBlank()) {
            sql.append(
                "AND (COALESCE(st.first_name, g.first_name, stu.first_name) ILIKE ? " +
                " OR COALESCE(st.last_name, g.last_name, stu.last_name) ILIKE ? " +
                " OR ua.email ILIKE ? OR ua.phone ILIKE ?) "
            );
            String like = "%" + q + "%";
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        sql.append("ORDER BY first_name, last_name");
        return jdbc.query(
            sql.toString(),
            (rs, i) -> {
                String first = rs.getString("first_name");
                String last = rs.getString("last_name");
                String name = first == null ? rs.getString("subject_type") : (last == null ? first : first + " " + last);
                return new UserDirectoryEntryDto(
                    UUID.fromString(rs.getString("user_account_id")),
                    rs.getString("subject_type"),
                    rs.getString("subject_id") == null ? null : UUID.fromString(rs.getString("subject_id")),
                    name,
                    rs.getString("email"),
                    rs.getString("phone")
                );
            },
            args.toArray()
        );
    }

    private RowMapper<GuardianDto> guardianMapper() {
        return (rs, i) -> new GuardianDto(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("school_id")),
            rs.getString("first_name"),
            rs.getString("last_name"),
            rs.getString("phone"),
            rs.getString("email"),
            rs.getBoolean("opt_in_whatsapp"),
            rs.getBoolean("opt_in_push"),
            rs.getBoolean("opt_in_email")
        );
    }
}
