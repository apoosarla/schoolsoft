package com.schoolsoft.enrolment.api;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The one answer to "is this child at this school?" — and the one place the
 * answer is written down.
 *
 * <h2>Why it is a date and not a status</h2>
 * Every module used to spell the question {@code e.status = 'active'}, in
 * eighteen separate SQL strings. That works right up to the moment a school
 * files a withdrawal: the paperwork is done on the 1st, the child's last day is
 * the 30th, and for those thirty days {@code status} has to say one thing to the
 * register (they are here) and another to the office (they are leaving). A
 * single column cannot, so with a status-driven predicate the school gets to
 * pick which one is broken — either the child vanishes off the attendance sheet
 * a month early, or their parent is still getting section circulars in November.
 *
 * <p>So {@code status} is now the <em>reason</em> an enrolment closed
 * ({@code withdrawn}, {@code transferred}, {@code graduated}, {@code promoted},
 * {@code detained}) and the dates are the authority on whether it is open:</p>
 *
 * <pre>starts_on &lt;= d AND (ends_on IS NULL OR ends_on &gt;= d)</pre>
 *
 * <p>{@code ends_on} is inclusive — the last day the child counts — which is
 * why a withdrawal writes the last working date into it verbatim and the roster
 * drops them the following morning, with no job to run and nothing to remember.
 * History stays queryable because nothing is deleted: asking the predicate about
 * March returns March's class list (XFER-03).</p>
 *
 * <h2>Using it</h2>
 * A read that filters a list embeds {@link #activeOn(String)} in its SQL and
 * passes the date twice, in place. A read that asks about one child calls
 * {@link #isActiveOn} or {@link #activeEnrolmentOn}.
 *
 * <pre>{@code
 * "SELECT ... FROM enrolment e WHERE e.section_id = ? AND " + EnrolmentActivity.activeOn("e")
 * // args: sectionId, date, date
 * }</pre>
 */
@Service
public class EnrolmentActivity {

    private final DataSource dataSource;

    public EnrolmentActivity(DataSource dataSource) { this.dataSource = dataSource; }

    /**
     * The predicate, for an enrolment table aliased {@code alias}. Takes two
     * positional parameters — the date, twice — in the order they appear.
     *
     * <p>Deliberately a string rather than a view: the reads that need it join
     * five other tables and filter on their own columns, and a view would either
     * hide those joins or duplicate them.</p>
     */
    public static String activeOn(String alias) {
        return "(" + alias + ".starts_on <= ? AND (" + alias + ".ends_on IS NULL OR " + alias + ".ends_on >= ?))";
    }

    /**
     * As {@link #activeOn}, for a statement that binds the date once — the
     * common case where the caller can name a SQL parameter twice more cheaply
     * than it can pass another argument.
     */
    public static String activeOnDateLiteral(String alias, LocalDate date) {
        return "(" + alias + ".starts_on <= DATE '" + date + "' AND (" + alias + ".ends_on IS NULL OR "
            + alias + ".ends_on >= DATE '" + date + "'))";
    }

    /** Today, for the callers that do not take a date from their caller. */
    public static LocalDate orToday(LocalDate date) {
        return date == null ? LocalDate.now() : date;
    }

    // ------------------------------------------------------------ the queries

    /** True when the student was on the school's books on {@code date}. */
    public boolean isActiveOn(UUID studentId, LocalDate date) {
        return activeEnrolmentOn(studentId, date).isPresent();
    }

    /**
     * The student's enrolment as at {@code date}, if they had one.
     *
     * <p>Ordered so that on a section-transfer day — where the outgoing row
     * closes and the incoming one opens — the still-open enrolment wins. The
     * two no longer overlap ({@code V031} corrected the rows and
     * {@link EnrolmentRepository#transfer} stopped making them), but a lookup
     * that would return either at random is not something to leave to a
     * migration holding.</p>
     */
    public Optional<UUID> activeEnrolmentOn(UUID studentId, LocalDate date) {
        LocalDate d = orToday(date);
        List<UUID> rows = new JdbcTemplate(dataSource).query(
            "SELECT e.id FROM enrolment e WHERE e.student_id = ? AND " + activeOn("e")
                + " ORDER BY (e.ends_on IS NULL) DESC, e.starts_on DESC LIMIT 1",
            (rs, i) -> UUID.fromString(rs.getString(1)),
            studentId, Date.valueOf(d), Date.valueOf(d));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** The students on a section's register on {@code date}, in roll order. */
    public List<UUID> studentsInSectionOn(UUID sectionId, LocalDate date) {
        LocalDate d = orToday(date);
        return new JdbcTemplate(dataSource).query(
            "SELECT e.student_id FROM enrolment e WHERE e.section_id = ? AND " + activeOn("e")
                + " ORDER BY e.roll_no",
            (rs, i) -> UUID.fromString(rs.getString(1)),
            sectionId, Date.valueOf(d), Date.valueOf(d));
    }
}
