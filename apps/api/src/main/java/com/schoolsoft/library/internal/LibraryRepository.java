package com.schoolsoft.library.internal;

import com.schoolsoft.library.api.LibraryCopyDto;
import com.schoolsoft.library.api.LibraryIssueDto;
import com.schoolsoft.library.api.LibraryTitleDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.sql.Array;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class LibraryRepository {

    private static final double LATE_FEE_PER_DAY = 2.0;

    private final JdbcTemplate jdbc;
    public LibraryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private Array textArray(List<String> values) {
        return jdbc.execute((ConnectionCallback<Array>) con -> con.createArrayOf("text", values == null ? new String[0] : values.toArray()));
    }

    // -------------------------- Titles --------------------------

    private static final RowMapper<LibraryTitleDto> TITLE_MAPPER = (rs, i) -> {
        Array tagsArr = rs.getArray("subject_tags");
        List<String> tags = tagsArr == null ? List.of() : Arrays.stream((Object[]) tagsArr.getArray()).map(Object::toString).toList();
        return new LibraryTitleDto(
            UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("school_id")), rs.getString("isbn"),
            rs.getString("title"), rs.getString("author"), rs.getString("publisher"), (Integer) rs.getObject("year"), tags
        );
    };

    public List<LibraryTitleDto> listTitles(UUID schoolId, String q) {
        String sql = "SELECT id, school_id, isbn, title, author, publisher, year, subject_tags FROM library_title WHERE school_id = ?" +
            (q == null || q.isBlank() ? "" : " AND (title ILIKE ? OR author ILIKE ? OR isbn = ?)") + " ORDER BY title";
        if (q == null || q.isBlank()) return jdbc.query(sql, TITLE_MAPPER, schoolId);
        String like = "%" + q + "%";
        return jdbc.query(sql, TITLE_MAPPER, schoolId, like, like, q);
    }

    public LibraryTitleDto createTitle(
        UUID schoolId, String isbn, String title, String author, String publisher, Integer year, List<String> subjectTags
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO library_title (id, school_id, isbn, title, author, publisher, year, subject_tags) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, isbn, title, author, publisher, year, textArray(subjectTags)
        );
        return jdbc.queryForObject(
            "SELECT id, school_id, isbn, title, author, publisher, year, subject_tags FROM library_title WHERE id = ?",
            TITLE_MAPPER, id
        );
    }

    // -------------------------- Copies --------------------------

    private static final RowMapper<LibraryCopyDto> COPY_MAPPER = (rs, i) -> new LibraryCopyDto(
        UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("title_id")), rs.getString("barcode"), rs.getString("status")
    );

    public List<LibraryCopyDto> listCopies(UUID titleId) {
        return jdbc.query("SELECT id, title_id, barcode, status FROM library_copy WHERE title_id = ? ORDER BY barcode", COPY_MAPPER, titleId);
    }

    public LibraryCopyDto addCopy(UUID titleId, String barcode) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO library_copy (id, title_id, barcode) VALUES (?, ?, ?)", id, titleId, barcode);
        return jdbc.queryForObject("SELECT id, title_id, barcode, status FROM library_copy WHERE id = ?", COPY_MAPPER, id);
    }

    // -------------------------- Issue / Return --------------------------

    private static final RowMapper<LibraryIssueDto> ISSUE_MAPPER = (rs, i) -> new LibraryIssueDto(
        UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("copy_id")), rs.getString("member_type"),
        UUID.fromString(rs.getString("member_id")), rs.getDate("issued_on").toLocalDate(), rs.getDate("due_on").toLocalDate(),
        rs.getDate("returned_on") == null ? null : rs.getDate("returned_on").toLocalDate(),
        rs.getDouble("fine"), rs.getBoolean("fine_paid")
    );

    private static final String ISSUE_COLS =
        "id, copy_id, member_type, member_id, issued_on, due_on, returned_on, fine, fine_paid";

    public LibraryIssueDto issue(UUID schoolId, UUID copyId, String memberType, UUID memberId, LocalDate dueOn) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO library_issue (id, school_id, copy_id, member_type, member_id, due_on) VALUES (?, ?, ?, ?, ?, ?)",
            id, schoolId, copyId, memberType, memberId, Date.valueOf(dueOn)
        );
        jdbc.update("UPDATE library_copy SET status = 'issued' WHERE id = ?", copyId);
        return jdbc.queryForObject("SELECT " + ISSUE_COLS + " FROM library_issue WHERE id = ?", ISSUE_MAPPER, id);
    }

    public LibraryIssueDto returnCopy(UUID issueId) {
        var rows = jdbc.query("SELECT " + ISSUE_COLS + " FROM library_issue WHERE id = ?", ISSUE_MAPPER, issueId);
        if (rows.isEmpty()) throw new NotFoundException("Library issue not found: " + issueId);
        var issue = rows.get(0);
        LocalDate today = LocalDate.now();
        long lateDays = Math.max(0, ChronoUnit.DAYS.between(issue.dueOn(), today));
        double fine = lateDays * LATE_FEE_PER_DAY;
        jdbc.update(
            "UPDATE library_issue SET returned_on = ?, fine = ? WHERE id = ?", Date.valueOf(today), fine, issueId
        );
        jdbc.update("UPDATE library_copy SET status = 'available' WHERE id = ?", issue.copyId());
        return jdbc.queryForObject("SELECT " + ISSUE_COLS + " FROM library_issue WHERE id = ?", ISSUE_MAPPER, issueId);
    }

    public List<LibraryIssueDto> listActiveForMember(String memberType, UUID memberId) {
        return jdbc.query(
            "SELECT " + ISSUE_COLS + " FROM library_issue WHERE member_type = ? AND member_id = ? AND returned_on IS NULL",
            ISSUE_MAPPER, memberType, memberId
        );
    }
}
