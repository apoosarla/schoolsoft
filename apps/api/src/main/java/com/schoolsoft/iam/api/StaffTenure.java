package com.schoolsoft.iam.api;

import com.schoolsoft.platform.security.Perm;
import com.schoolsoft.platform.time.SchoolClock;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The one answer to "is this person still on the school's books?" — the staff
 * sibling of {@code EnrolmentActivity}, and a question about a date for the
 * same reason that one is.
 *
 * <p>An exit is filed ahead of the day it takes effect. Between the two the
 * person still teaches, still marks a register and still signs in; the morning
 * after their last working day they do none of it. {@code staff.left_on} is
 * that last day, inclusive, and the predicate is the whole of the mechanism:</p>
 *
 * <pre>left_on IS NULL OR left_on &gt;= d</pre>
 *
 * <p>Sign-in, token refresh and {@link PermissionChecker} all ask it, so access
 * ends at the school's midnight with no job to run and no row to flip. Role
 * grants are left standing — they are the record of what the person held — and
 * simply stop counting.</p>
 *
 * <p>It lives here rather than beside the staff table because the callers that
 * cannot afford to get it wrong are the ones deciding who gets in.</p>
 */
@Service
public class StaffTenure {

    private final DataSource dataSource;
    private final SchoolClock clock;

    public StaffTenure(DataSource dataSource, SchoolClock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /**
     * The predicate, for a staff table aliased {@code alias}. Takes one
     * positional parameter — the date.
     */
    public static String onBooks(String alias) {
        return "(" + alias + ".left_on IS NULL OR " + alias + ".left_on >= ?)";
    }

    /** True when the staff member is, or was, on the books on {@code date}. Unknown ids are not. */
    public boolean isOnBooks(UUID staffId, LocalDate date) {
        Integer n = new JdbcTemplate(dataSource).queryForObject(
            "SELECT count(*) FROM staff s WHERE s.id = ? AND " + onBooks("s"),
            Integer.class, staffId, java.sql.Date.valueOf(date));
        return n != null && n > 0;
    }

    /**
     * Whether the account may still act today. An account that is not a staff
     * member's — a guardian, a chain admin — has no tenure to end and always
     * may; so does a staff account whose staff row is missing, which is a
     * different defect and not this class's to hide.
     */
    public boolean accountIsOnBooks(UUID userAccountId, UUID schoolId) {
        Integer gone = new JdbcTemplate(dataSource).queryForObject(
            "SELECT count(*) FROM user_account ua JOIN staff s ON s.id = ua.subject_id " +
            "WHERE ua.id = ? AND ua.subject_type = 'staff' AND NOT " + onBooks("s"),
            Integer.class, userAccountId, java.sql.Date.valueOf(clock.today(schoolId)));
        return gone == null || gone == 0;
    }

    /**
     * Whether the staff member holds a teaching grant — {@code mark.enter} or
     * {@code attendance.mark}, the same two {@link TeacherScope} reads to
     * decide who is a teacher. Asked of {@code role_perm} rather than of role
     * names, so a school's own "PE Instructor" role qualifies without a deploy
     * and a head who also takes a class qualifies because a head holds
     * everything (STF-01).
     */
    public boolean canTeach(UUID staffId) {
        Integer n = new JdbcTemplate(dataSource).queryForObject(
            "SELECT count(*) FROM staff_role sr JOIN role_perm rp ON rp.role_code = sr.role_code " +
            "WHERE sr.staff_id = ? AND sr.revoked_at IS NULL AND rp.perm_code IN (?, ?)",
            Integer.class, staffId, Perm.MARK_ENTER.code(), Perm.ATTENDANCE_MARK.code());
        return n != null && n > 0;
    }
}
