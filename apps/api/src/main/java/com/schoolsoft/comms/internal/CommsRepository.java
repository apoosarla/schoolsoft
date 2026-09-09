package com.schoolsoft.comms.internal;

import com.schoolsoft.comms.api.AnnouncementDto;
import com.schoolsoft.comms.api.MessageDto;
import com.schoolsoft.comms.api.MessageThreadDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CommsRepository {

    private final JdbcTemplate jdbc;
    public CommsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private Array uuidArray(List<UUID> ids) {
        return jdbc.execute((ConnectionCallback<Array>) con -> con.createArrayOf("uuid", ids == null ? new UUID[0] : ids.toArray()));
    }

    private Array textArray(List<String> values) {
        return jdbc.execute((ConnectionCallback<Array>) con -> con.createArrayOf("text", values == null ? new String[0] : values.toArray()));
    }

    private static List<UUID> readUuidArray(ResultSet rs, String col) throws SQLException {
        Array arr = rs.getArray(col);
        if (arr == null) return List.of();
        Object[] raw = (Object[]) arr.getArray();
        return Arrays.stream(raw).map(o -> UUID.fromString(o.toString())).toList();
    }

    private static List<String> readTextArray(ResultSet rs, String col) throws SQLException {
        Array arr = rs.getArray(col);
        if (arr == null) return List.of();
        Object[] raw = (Object[]) arr.getArray();
        return Arrays.stream(raw).map(Object::toString).toList();
    }

    // -------------------------- Announcements --------------------------

    private static final RowMapper<AnnouncementDto> ANNOUNCEMENT_MAPPER = (rs, i) -> new AnnouncementDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("scope_type"),
        readUuidArray(rs, "scope_ids"),
        rs.getString("title"),
        rs.getString("body"),
        readTextArray(rs, "channels"),
        rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
        rs.getString("created_by_user_id") == null ? null : UUID.fromString(rs.getString("created_by_user_id")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("priority")
    );

    private static final String ANNOUNCEMENT_COLS =
        "id, school_id, scope_type, scope_ids, title, body, channels, published_at, expires_at, "
        + "created_by_user_id, created_at, priority";

    public List<AnnouncementDto> list(UUID schoolId) {
        return jdbc.query(
            "SELECT " + ANNOUNCEMENT_COLS + " FROM announcement WHERE school_id = ? ORDER BY created_at DESC",
            ANNOUNCEMENT_MAPPER, schoolId
        );
    }

    public AnnouncementDto create(
        UUID schoolId, String scopeType, List<UUID> scopeIds, String title, String body,
        List<String> channels, UUID createdByUserId, String priority
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO announcement (id, school_id, scope_type, scope_ids, title, body, channels, " +
            "  created_by_user_id, priority) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, scopeType, uuidArray(scopeIds), title, body,
            channels == null || channels.isEmpty() ? textArray(List.of("push", "email")) : textArray(channels),
            createdByUserId, priority == null ? "normal" : priority
        );
        return find(id);
    }

    public AnnouncementDto find(UUID id) {
        var rows = jdbc.query("SELECT " + ANNOUNCEMENT_COLS + " FROM announcement WHERE id = ?", ANNOUNCEMENT_MAPPER, id);
        if (rows.isEmpty()) throw new NotFoundException("Announcement not found: " + id);
        return rows.get(0);
    }

    /**
     * Stamps the publish time, once. Returns false when the announcement was
     * already published — a retry of a publish that worked is not an error,
     * but it is not a second broadcast either.
     */
    public boolean markPublished(UUID id) {
        int updated = jdbc.update(
            "UPDATE announcement SET published_at = now() WHERE id = ? AND published_at IS NULL", id);
        if (updated == 0) find(id);   // 404 for an id that never existed, rather than a silent false
        return updated == 1;
    }

    /**
     * Who an announcement is addressed to, as students — the notification
     * module turns those into the guardians that actually receive it.
     *
     * <p>Only an active enrolment is in scope: a circular to "the school" is a
     * circular to the children currently at it.</p>
     */
    public List<UUID> audienceStudentIds(AnnouncementDto announcement) {
        List<UUID> scopeIds = announcement.scopeIds() == null ? List.of() : announcement.scopeIds();
        return switch (announcement.scopeType()) {
            case "school" -> jdbc.query(
                "SELECT DISTINCT e.student_id FROM enrolment e WHERE e.school_id = ? AND e.status = 'active'",
                (rs, i) -> UUID.fromString(rs.getString("student_id")),
                announcement.schoolId());
            case "grade" -> scopeIds.isEmpty() ? List.of() : jdbc.query(
                "SELECT DISTINCT e.student_id FROM enrolment e JOIN section s ON s.id = e.section_id " +
                "WHERE e.school_id = ? AND e.status = 'active' AND s.grade_id IN (" + placeholders(scopeIds) + ")",
                (rs, i) -> UUID.fromString(rs.getString("student_id")),
                prepend(announcement.schoolId(), scopeIds));
            case "section" -> scopeIds.isEmpty() ? List.of() : jdbc.query(
                "SELECT DISTINCT e.student_id FROM enrolment e " +
                "WHERE e.school_id = ? AND e.status = 'active' AND e.section_id IN (" + placeholders(scopeIds) + ")",
                (rs, i) -> UUID.fromString(rs.getString("student_id")),
                prepend(announcement.schoolId(), scopeIds));
            // 'custom' names the children directly. Still filtered through
            // enrolment so a hand-typed id from another school reaches nobody.
            case "custom" -> scopeIds.isEmpty() ? List.of() : jdbc.query(
                "SELECT DISTINCT e.student_id FROM enrolment e " +
                "WHERE e.school_id = ? AND e.status = 'active' AND e.student_id IN (" + placeholders(scopeIds) + ")",
                (rs, i) -> UUID.fromString(rs.getString("student_id")),
                prepend(announcement.schoolId(), scopeIds));
            default -> List.of();
        };
    }

    private static String placeholders(List<UUID> ids) {
        return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    }

    private static Object[] prepend(UUID first, List<UUID> rest) {
        Object[] args = new Object[rest.size() + 1];
        args[0] = first;
        for (int i = 0; i < rest.size(); i++) args[i + 1] = rest.get(i);
        return args;
    }

    public void markRead(UUID announcementId, UUID userAccountId) {
        jdbc.update(
            "INSERT INTO announcement_read (announcement_id, user_account_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            announcementId, userAccountId
        );
    }

    // -------------------------- Messaging --------------------------

    private static final RowMapper<MessageThreadDto> THREAD_MAPPER = (rs, i) -> new MessageThreadDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("subject_student_id") == null ? null : UUID.fromString(rs.getString("subject_student_id")),
        readUuidArray(rs, "participants"),
        rs.getTimestamp("last_message_at") == null ? null : rs.getTimestamp("last_message_at").toInstant()
    );

    private static final String THREAD_COLS = "id, school_id, subject_student_id, participants, last_message_at";

    public List<MessageThreadDto> threadsForParticipant(UUID userAccountId) {
        return jdbc.query(
            "SELECT " + THREAD_COLS + " FROM message_thread WHERE ? = ANY(participants) ORDER BY last_message_at DESC NULLS LAST",
            THREAD_MAPPER, userAccountId
        );
    }

    public Optional<MessageThreadDto> findThread(UUID id) {
        var rows = jdbc.query("SELECT " + THREAD_COLS + " FROM message_thread WHERE id = ?", THREAD_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public MessageThreadDto createThread(UUID schoolId, UUID subjectStudentId, List<UUID> participants) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO message_thread (id, school_id, subject_student_id, participants) VALUES (?, ?, ?, ?)",
            id, schoolId, subjectStudentId, uuidArray(participants)
        );
        return findThread(id).orElseThrow();
    }

    private static final RowMapper<MessageDto> MESSAGE_MAPPER = (rs, i) -> new MessageDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("thread_id")),
        UUID.fromString(rs.getString("sender_user_id")),
        rs.getString("body"),
        rs.getTimestamp("sent_at").toInstant()
    );

    public List<MessageDto> listMessages(UUID threadId) {
        return jdbc.query(
            // `message` carries no school_id; message_thread does, and its RLS
            // policy is what keeps one school's conversations out of another's.
            "SELECT m.id, m.thread_id, m.sender_user_id, m.body, m.sent_at FROM message m " +
            "JOIN message_thread t ON t.id = m.thread_id WHERE m.thread_id = ? ORDER BY m.sent_at",
            MESSAGE_MAPPER, threadId
        );
    }

    public MessageDto sendMessage(UUID threadId, UUID senderUserId, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO message (id, thread_id, sender_user_id, body) VALUES (?, ?, ?, ?)", id, threadId, senderUserId, body);
        jdbc.update("UPDATE message_thread SET last_message_at = now() WHERE id = ?", threadId);
        return jdbc.queryForObject(
            "SELECT m.id, m.thread_id, m.sender_user_id, m.body, m.sent_at FROM message m " +
            "JOIN message_thread t ON t.id = m.thread_id WHERE m.id = ?", MESSAGE_MAPPER, id
        );
    }
}
