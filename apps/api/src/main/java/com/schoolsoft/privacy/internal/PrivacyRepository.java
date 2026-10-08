package com.schoolsoft.privacy.internal;

import com.schoolsoft.privacy.api.ConsentDto;
import com.schoolsoft.privacy.api.DataRequestDto;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PrivacyRepository {

    private static final String SCHOOL_OF_STUDENT = "SELECT school_id FROM student WHERE id = ?";

    private static final String CONSENTS =
        "SELECT id, subject_id, purpose, granted_at, revoked_at, source FROM consent_record "
            + "WHERE subject_type = 'student' AND subject_id = ? AND granted "
            + "ORDER BY granted_at DESC";

    // The partial unique index is the guard: a second grant while one stands
    // inserts nothing, so a retry is not a second consent.
    private static final String GRANT =
        "INSERT INTO consent_record (school_id, subject_type, subject_id, purpose, granted, source, "
            + "recorded_by_user_id) VALUES (?, 'student', ?, ?, TRUE, ?, ?) "
            + "ON CONFLICT (subject_type, subject_id, purpose) WHERE revoked_at IS NULL DO NOTHING";

    private static final String WITHDRAW =
        "UPDATE consent_record SET revoked_at = now() "
            + "WHERE subject_type = 'student' AND subject_id = ? AND purpose = ? AND revoked_at IS NULL";

    private static final String WITHDRAW_ALL =
        "UPDATE consent_record SET revoked_at = now() "
            + "WHERE subject_type = 'student' AND subject_id = ? AND revoked_at IS NULL";

    private static final String REQUEST_COLUMNS =
        "SELECT id, school_id, student_id, kind, status, note, requested_on, due_on, decided_at, "
            + "decision_reason FROM data_request ";

    // An access request has nothing for the office to decide, so it is born
    // served. `decided_at` follows the status in the same statement because
    // the table's CHECK ties the two together.
    private static final String FILE =
        "INSERT INTO data_request (school_id, student_id, kind, status, requested_by_user_id, note, "
            + "requested_on, due_on, decided_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'open' THEN NULL ELSE now() END) "
            + "ON CONFLICT (student_id, kind) WHERE status = 'open' DO NOTHING RETURNING id";

    private static final String OPEN_REQUEST =
        "SELECT id FROM data_request WHERE student_id = ? AND kind = ? AND status = 'open'";

    // The transition names the state it leaves: zero rows means somebody
    // decided this request first, and the caller reads what they decided.
    private static final String DECIDE =
        "UPDATE data_request SET status = ?, decided_at = now(), decided_by_user_id = ?, "
            + "decision_reason = ? WHERE id = ? AND status = 'open'";

    private static final String STILL_ENROLLED =
        "SELECT count(*) FROM enrolment WHERE student_id = ? AND (ends_on IS NULL OR ends_on >= ?)";

    private final JdbcTemplate jdbc;

    public PrivacyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<UUID> schoolOfStudent(UUID studentId) {
        return jdbc.query(SCHOOL_OF_STUDENT, (rs, i) -> UUID.fromString(rs.getString(1)), studentId)
            .stream().findFirst();
    }

    public List<ConsentDto> consents(UUID studentId) {
        return jdbc.query(CONSENTS, (rs, i) -> new ConsentDto(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("subject_id")),
            rs.getString("purpose"),
            rs.getTimestamp("revoked_at") == null,
            rs.getTimestamp("granted_at").toInstant(),
            rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
            rs.getString("source")), studentId);
    }

    public void grant(UUID schoolId, UUID studentId, String purpose, String source, UUID recordedBy) {
        jdbc.update(GRANT, schoolId, studentId, purpose, source, recordedBy);
    }

    public void withdraw(UUID studentId, String purpose) {
        jdbc.update(WITHDRAW, studentId, purpose);
    }

    public void withdrawAll(UUID studentId) {
        jdbc.update(WITHDRAW_ALL, studentId);
    }

    /** The new request's id, or the id of the open one this would have duplicated. */
    public UUID file(UUID schoolId, UUID studentId, String kind, String status, UUID requestedBy,
                     String note, LocalDate requestedOn, LocalDate dueOn) {
        return jdbc.query(FILE, (rs, i) -> UUID.fromString(rs.getString(1)),
                schoolId, studentId, kind, status, requestedBy, note,
                Date.valueOf(requestedOn), Date.valueOf(dueOn), status)
            .stream().findFirst()
            .orElseGet(() -> jdbc.queryForObject(OPEN_REQUEST,
                (rs, i) -> UUID.fromString(rs.getString(1)), studentId, kind));
    }

    public Optional<DataRequestDto> request(UUID id, LocalDate today) {
        return jdbc.query(REQUEST_COLUMNS + "WHERE id = ?", requestMapper(today), id).stream().findFirst();
    }

    public List<DataRequestDto> requests(String status, LocalDate today) {
        return status == null
            ? jdbc.query(REQUEST_COLUMNS + "ORDER BY due_on, created_at", requestMapper(today))
            : jdbc.query(REQUEST_COLUMNS + "WHERE status = ? ORDER BY due_on, created_at",
                requestMapper(today), status);
    }

    public boolean decide(UUID id, String status, UUID decidedBy, String reason) {
        return jdbc.update(DECIDE, status, decidedBy, reason, id) == 1;
    }

    /** True while any enrolment has not ended by {@code today} — current, or yet to start. */
    public boolean stillEnrolled(UUID studentId, LocalDate today) {
        Integer n = jdbc.queryForObject(STILL_ENROLLED, Integer.class, studentId, Date.valueOf(today));
        return n != null && n > 0;
    }

    private static RowMapper<DataRequestDto> requestMapper(LocalDate today) {
        return (rs, i) -> {
            LocalDate dueOn = rs.getDate("due_on").toLocalDate();
            String status = rs.getString("status");
            return new DataRequestDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("school_id")),
                UUID.fromString(rs.getString("student_id")),
                rs.getString("kind"), status, rs.getString("note"),
                rs.getDate("requested_on").toLocalDate(), dueOn,
                "open".equals(status) && today.isAfter(dueOn),
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
                rs.getString("decision_reason"));
        };
    }
}
