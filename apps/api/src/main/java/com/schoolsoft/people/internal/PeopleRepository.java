package com.schoolsoft.people.internal;

import com.schoolsoft.people.api.GuardianDto;
import com.schoolsoft.people.api.PeopleController;
import com.schoolsoft.people.api.StaffDto;
import com.schoolsoft.people.api.StudentDto;
import com.schoolsoft.people.api.UserDirectoryEntryDto;
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
    public PeopleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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

    public List<StudentDto> listStudents(UUID schoolId, UUID sectionId, String q, int limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT s.id, s.school_id, s.admission_no, s.first_name, s.middle_name, s.last_name, " +
            "       s.dob, s.gender, s.status, " +
            "       e.section_id, (g.code || '-' || sec.code) AS section_label, e.roll_no " +
            "FROM student s " +
            "LEFT JOIN enrolment e ON e.student_id = s.id AND e.status = 'active' " +
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
            "LEFT JOIN enrolment e ON e.student_id = s.id AND e.status = 'active' " +
            "LEFT JOIN section sec ON sec.id = e.section_id " +
            "LEFT JOIN grade   g   ON g.id = sec.grade_id " +
            "WHERE s.id = ?",
            STUDENT, id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public StudentDto createStudent(PeopleController.CreateStudentRequest req) {
        UUID id = UUID.randomUUID();
        Date dob = req.dob() == null ? null : Date.valueOf(LocalDate.parse(req.dob()));
        jdbc.update(
            "INSERT INTO student (id, school_id, admission_no, first_name, middle_name, last_name, dob, gender) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, req.schoolId(), req.admissionNo(), req.firstName(), req.middleName(), req.lastName(), dob, req.gender()
        );
        return findStudent(id).orElseThrow();
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

    public List<StaffDto> listStaff(UUID schoolId, String q) {
        String sql =
            "SELECT id, school_id, employee_no, first_name, last_name, email, phone, " +
            "       employment_type, joined_on, is_active " +
            "FROM staff WHERE school_id = ?" +
            (q == null || q.isBlank() ? "" : " AND (email ILIKE ? OR first_name ILIKE ? OR employee_no = ?)") +
            " ORDER BY first_name";
        RowMapper<StaffDto> mapper = (rs, i) -> new StaffDto(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("school_id")),
            rs.getString("employee_no"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            rs.getString("email"),
            rs.getString("phone"),
            rs.getString("employment_type"),
            rs.getDate("joined_on") == null ? null : rs.getDate("joined_on").toLocalDate(),
            rs.getBoolean("is_active")
        );
        if (q == null || q.isBlank()) return jdbc.query(sql, mapper, schoolId);
        String like = "%" + q + "%";
        return jdbc.query(sql, mapper, schoolId, like, like, q);
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
