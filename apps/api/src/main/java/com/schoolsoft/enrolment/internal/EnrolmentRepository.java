package com.schoolsoft.enrolment.internal;

import com.schoolsoft.enrolment.api.EnrolmentDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class EnrolmentRepository {

    private final JdbcTemplate jdbc;
    public EnrolmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<EnrolmentDto> MAPPER = (rs, i) -> new EnrolmentDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("student_id")),
        UUID.fromString(rs.getString("section_id")),
        rs.getString("section_label"),
        UUID.fromString(rs.getString("academic_year_id")),
        rs.getDate("starts_on").toLocalDate(),
        rs.getDate("ends_on") == null ? null : rs.getDate("ends_on").toLocalDate(),
        rs.getString("status"),
        rs.getString("roll_no")
    );

    private static final String SELECT =
        "SELECT e.id, e.school_id, e.student_id, e.section_id, (g.code || '-' || sec.code) AS section_label, " +
        "       e.academic_year_id, e.starts_on, e.ends_on, e.status, e.roll_no " +
        "FROM enrolment e JOIN section sec ON sec.id = e.section_id JOIN grade g ON g.id = sec.grade_id ";

    public List<EnrolmentDto> listByStudent(UUID studentId) {
        return jdbc.query(SELECT + "WHERE e.student_id = ? ORDER BY e.starts_on DESC", MAPPER, studentId);
    }

    public List<EnrolmentDto> listBySection(UUID sectionId, boolean activeOnly) {
        String sql = SELECT + "WHERE e.section_id = ?" + (activeOnly ? " AND e.status = 'active'" : "") + " ORDER BY e.roll_no";
        return jdbc.query(sql, MAPPER, sectionId);
    }

    public Optional<EnrolmentDto> findActiveByStudent(UUID studentId) {
        var rows = jdbc.query(SELECT + "WHERE e.student_id = ? AND e.status = 'active'", MAPPER, studentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<EnrolmentDto> find(UUID id) {
        var rows = jdbc.query(SELECT + "WHERE e.id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public EnrolmentDto enrol(UUID schoolId, UUID studentId, UUID sectionId, UUID academicYearId, LocalDate startsOn, String rollNo) {
        if (findActiveByStudent(studentId).isPresent()) {
            throw new IllegalArgumentException("Student already has an active enrolment; withdraw or transfer first");
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, starts_on, status, roll_no) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'active', ?)",
            id, schoolId, studentId, sectionId, academicYearId, Date.valueOf(startsOn), rollNo
        );
        return find(id).orElseThrow();
    }

    /** Closes the current enrolment and opens a new active one in {@code newSectionId}, same academic year. */
    public EnrolmentDto transfer(UUID enrolmentId, UUID newSectionId, String rollNo) {
        var current = find(enrolmentId).orElseThrow(() -> new NotFoundException("Enrolment not found: " + enrolmentId));
        LocalDate today = LocalDate.now();
        jdbc.update(
            "UPDATE enrolment SET status = 'transferred', ends_on = ? WHERE id = ?",
            Date.valueOf(today), enrolmentId
        );
        UUID newId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, starts_on, status, roll_no) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'active', ?)",
            newId, current.schoolId(), current.studentId(), newSectionId, current.academicYearId(), Date.valueOf(today), rollNo
        );
        return find(newId).orElseThrow();
    }

    public EnrolmentDto setStatus(UUID enrolmentId, String status, LocalDate endsOn) {
        jdbc.update(
            "UPDATE enrolment SET status = ?, ends_on = ? WHERE id = ?",
            status, endsOn == null ? null : Date.valueOf(endsOn), enrolmentId
        );
        return find(enrolmentId).orElseThrow(() -> new NotFoundException("Enrolment not found: " + enrolmentId));
    }
}
