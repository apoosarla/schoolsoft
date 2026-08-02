package com.schoolsoft.admissions.internal;

import com.schoolsoft.admissions.api.AdmissionApplicationDto;
import com.schoolsoft.admissions.api.AdmissionEventDto;
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
public class AdmissionsRepository {

    private final JdbcTemplate jdbc;
    public AdmissionsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<AdmissionApplicationDto> MAPPER = (rs, i) -> new AdmissionApplicationDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        UUID.fromString(rs.getString("academic_year_id")),
        UUID.fromString(rs.getString("grade_id")),
        rs.getString("application_no"),
        rs.getString("applicant_first_name"),
        rs.getString("applicant_last_name"),
        rs.getDate("applicant_dob") == null ? null : rs.getDate("applicant_dob").toLocalDate(),
        rs.getString("applicant_gender"),
        rs.getString("guardian_name"),
        rs.getString("guardian_phone"),
        rs.getString("guardian_email"),
        rs.getString("source"),
        rs.getString("state"),
        (Double) rs.getObject("test_score"),
        rs.getString("interview_notes"),
        rs.getDate("offer_expires_on") == null ? null : rs.getDate("offer_expires_on").toLocalDate(),
        rs.getString("converted_student_id") == null ? null : UUID.fromString(rs.getString("converted_student_id")),
        rs.getTimestamp("created_at").toInstant()
    );

    private static final String COLS =
        "id, school_id, academic_year_id, grade_id, application_no, applicant_first_name, applicant_last_name, " +
        "applicant_dob, applicant_gender, guardian_name, guardian_phone, guardian_email, source, state, " +
        "test_score, interview_notes, offer_expires_on, converted_student_id, created_at";

    public List<AdmissionApplicationDto> list(UUID schoolId, String state) {
        String sql = "SELECT " + COLS + " FROM admission_application WHERE school_id = ?" +
            (state == null ? "" : " AND state = ?") + " ORDER BY created_at DESC";
        return state == null ? jdbc.query(sql, MAPPER, schoolId) : jdbc.query(sql, MAPPER, schoolId, state);
    }

    public Optional<AdmissionApplicationDto> find(UUID id) {
        var rows = jdbc.query("SELECT " + COLS + " FROM admission_application WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public AdmissionApplicationDto create(
        UUID schoolId, UUID academicYearId, UUID gradeId, String applicationNo,
        String firstName, String lastName, LocalDate dob, String gender,
        String guardianName, String guardianPhone, String guardianEmail, String source
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO admission_application (id, school_id, academic_year_id, grade_id, application_no, " +
            "  applicant_first_name, applicant_last_name, applicant_dob, applicant_gender, " +
            "  guardian_name, guardian_phone, guardian_email, source) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, academicYearId, gradeId, applicationNo, firstName, lastName,
            dob == null ? null : Date.valueOf(dob), gender, guardianName, guardianPhone, guardianEmail, source
        );
        recordEvent(id, "state_change", "lead", "lead", null);
        return find(id).orElseThrow();
    }

    public AdmissionApplicationDto transition(UUID id, String toState, UUID actorUserId) {
        var current = find(id).orElseThrow(() -> new NotFoundException("Application not found: " + id));
        jdbc.update("UPDATE admission_application SET state = ?, updated_at = now() WHERE id = ?", toState, id);
        recordEvent(id, "state_change", current.state(), toState, actorUserId);
        return find(id).orElseThrow();
    }

    public AdmissionApplicationDto recordTestScore(UUID id, double score, String notes) {
        jdbc.update(
            "UPDATE admission_application SET test_score = ?, interview_notes = ?, updated_at = now() WHERE id = ?",
            score, notes, id
        );
        recordEvent(id, "test_done", null, null, null);
        return find(id).orElseThrow(() -> new NotFoundException("Application not found: " + id));
    }

    public List<AdmissionEventDto> listEvents(UUID applicationId) {
        return jdbc.query(
            "SELECT id, application_id, event_type, from_state, to_state, occurred_at FROM admission_event " +
            "WHERE application_id = ? ORDER BY occurred_at",
            (rs, i) -> new AdmissionEventDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("application_id")),
                rs.getString("event_type"),
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getTimestamp("occurred_at").toInstant()
            ),
            applicationId
        );
    }

    private void recordEvent(UUID applicationId, String eventType, String fromState, String toState, UUID actorUserId) {
        jdbc.update(
            "INSERT INTO admission_event (id, application_id, event_type, from_state, to_state, actor_user_id) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), applicationId, eventType, fromState, toState, actorUserId
        );
    }

    /**
     * Converts an accepted application into a {@code student} row plus an
     * active {@code enrolment} in {@code sectionId}. Marks the application
     * {@code enrolled}. Direct SQL against {@code student}/{@code enrolment}
     * (owned by the people/enrolment modules) mirrors the existing pattern of
     * cross-cutting reads elsewhere in this codebase (e.g. PeopleRepository
     * joining section/grade) rather than introducing a Java dependency.
     */
    public UUID convertToStudent(UUID applicationId, UUID sectionId, String rollNo) {
        var app = find(applicationId).orElseThrow(() -> new NotFoundException("Application not found: " + applicationId));
        UUID studentId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO student (id, school_id, admission_no, first_name, middle_name, last_name, dob, gender, status) " +
            "VALUES (?, ?, ?, ?, NULL, ?, ?, ?, 'active')",
            studentId, app.schoolId(), app.applicationNo(), app.applicantFirstName(), app.applicantLastName(),
            app.applicantDob() == null ? null : Date.valueOf(app.applicantDob()), app.applicantGender()
        );
        jdbc.update(
            "INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, starts_on, status, roll_no) " +
            "VALUES (?, ?, ?, ?, ?, CURRENT_DATE, 'active', ?)",
            UUID.randomUUID(), app.schoolId(), studentId, sectionId, app.academicYearId(), rollNo
        );
        jdbc.update(
            "UPDATE admission_application SET state = 'enrolled', converted_student_id = ?, updated_at = now() WHERE id = ?",
            studentId, applicationId
        );
        recordEvent(applicationId, "state_change", app.state(), "enrolled", null);
        return studentId;
    }
}
