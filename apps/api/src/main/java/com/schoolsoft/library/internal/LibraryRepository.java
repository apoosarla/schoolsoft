package com.schoolsoft.library.internal;

import com.schoolsoft.fees.api.FeeCharges;
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

    /** Used when a school has not configured a library_charge_policy. */
    private static final double DEFAULT_FINE_PER_DAY = 2.0;

    private final JdbcTemplate jdbc;
    private final FeeCharges feeCharges;

    public LibraryRepository(JdbcTemplate jdbc, FeeCharges feeCharges) {
        this.jdbc = jdbc;
        this.feeCharges = feeCharges;
    }

    private record ChargePolicy(double finePerDay, Double maxFine, double lostMultiplier, double damagedPct) {}

    private ChargePolicy policyFor(UUID schoolId) {
        var rows = jdbc.query(
            "SELECT fine_per_day, max_fine, lost_multiplier, damaged_pct FROM library_charge_policy " +
            "WHERE school_id = ?",
            (rs, i) -> new ChargePolicy(rs.getDouble("fine_per_day"),
                rs.getObject("max_fine") == null ? null : rs.getBigDecimal("max_fine").doubleValue(),
                rs.getDouble("lost_multiplier"), rs.getDouble("damaged_pct")),
            schoolId);
        return rows.isEmpty() ? new ChargePolicy(DEFAULT_FINE_PER_DAY, null, 1, 50) : rows.get(0);
    }

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

    /**
     * Returns a copy and, when it is late, posts the fine to the student's fee
     * ledger (LIB-03). A fine that lives only on the issue row is a number
     * nobody collects: it has to reach the invoice the parent is asked to pay
     * and the outstanding-dues report year-end clearance reads.
     */
    public LibraryIssueDto returnCopy(UUID issueId) {
        var rows = jdbc.query("SELECT " + ISSUE_COLS + " FROM library_issue WHERE id = ?", ISSUE_MAPPER, issueId);
        if (rows.isEmpty()) throw new NotFoundException("Library issue not found: " + issueId);
        var issue = rows.get(0);
        UUID schoolId = jdbc.queryForObject("SELECT school_id FROM library_issue WHERE id = ?",
            UUID.class, issueId);
        ChargePolicy policy = policyFor(schoolId);

        LocalDate today = LocalDate.now();
        long lateDays = Math.max(0, ChronoUnit.DAYS.between(issue.dueOn(), today));
        double fine = lateDays * policy.finePerDay();
        if (policy.maxFine() != null) fine = Math.min(fine, policy.maxFine());

        jdbc.update(
            "UPDATE library_issue SET returned_on = ?, fine = ?, fine_per_day = ?, " +
            "  charge_kind = CASE WHEN ? > 0 THEN 'overdue_fine' ELSE charge_kind END WHERE id = ?",
            Date.valueOf(today), fine, policy.finePerDay(), fine, issueId
        );
        jdbc.update("UPDATE library_copy SET status = 'available' WHERE id = ?", issue.copyId());

        if (fine > 0 && "student".equals(issue.memberType())) {
            var adjustment = feeCharges.chargeStudent(schoolId, issue.memberId(), "LIBRARY", fine,
                "Library fine — " + lateDays + " day(s) overdue", null);
            jdbc.update("UPDATE library_issue SET fee_adjustment_id = ? WHERE id = ?", adjustment.id(), issueId);
        }
        return jdbc.queryForObject("SELECT " + ISSUE_COLS + " FROM library_issue WHERE id = ?", ISSUE_MAPPER, issueId);
    }

    /**
     * A lost or damaged copy is charged at the title's price (times the
     * school's multiplier) and leaves circulation (LIB-04).
     */
    public LibraryIssueDto chargeLostOrDamaged(UUID issueId, String kind, Double overrideAmount, String notes) {
        if (!List.of("lost", "damaged").contains(kind)) {
            throw new IllegalArgumentException("kind must be lost or damaged");
        }
        var rows = jdbc.query("SELECT " + ISSUE_COLS + " FROM library_issue WHERE id = ?", ISSUE_MAPPER, issueId);
        if (rows.isEmpty()) throw new NotFoundException("Library issue not found: " + issueId);
        var issue = rows.get(0);
        UUID schoolId = jdbc.queryForObject("SELECT school_id FROM library_issue WHERE id = ?",
            UUID.class, issueId);
        ChargePolicy policy = policyFor(schoolId);

        var price = jdbc.query(
            "SELECT t.price FROM library_copy c JOIN library_title t ON t.id = c.title_id WHERE c.id = ?",
            (rs, i) -> (java.math.BigDecimal) rs.getObject("price"), issue.copyId())
            .stream().findFirst().orElse(null);
        double base = price == null ? 0 : price.doubleValue();
        double amount = overrideAmount != null ? overrideAmount
            : "lost".equals(kind) ? base * policy.lostMultiplier() : base * policy.damagedPct() / 100.0;
        if (amount <= 0) {
            throw new IllegalArgumentException(
                "No price on the title and no amount supplied — cannot charge for a " + kind + " copy");
        }

        jdbc.update("UPDATE library_copy SET status = ? WHERE id = ?",
            "lost".equals(kind) ? "lost" : "damaged", issue.copyId());
        jdbc.update(
            "UPDATE library_issue SET returned_on = COALESCE(returned_on, CURRENT_DATE), charge_kind = ? " +
            "WHERE id = ?", kind, issueId);

        if ("student".equals(issue.memberType())) {
            var adjustment = feeCharges.chargeStudent(schoolId, issue.memberId(), "LIBRARY", amount,
                "Library " + kind + " copy charge" + (notes == null ? "" : " — " + notes), null);
            jdbc.update("UPDATE library_issue SET fee_adjustment_id = ?, fine = fine + ? WHERE id = ?",
                adjustment.id(), amount, issueId);
        }
        return jdbc.queryForObject("SELECT " + ISSUE_COLS + " FROM library_issue WHERE id = ?", ISSUE_MAPPER, issueId);
    }

    public List<LibraryIssueDto> listActiveForMember(String memberType, UUID memberId) {
        return jdbc.query(
            "SELECT " + ISSUE_COLS + " FROM library_issue WHERE member_type = ? AND member_id = ? AND returned_on IS NULL",
            ISSUE_MAPPER, memberType, memberId
        );
    }
}
