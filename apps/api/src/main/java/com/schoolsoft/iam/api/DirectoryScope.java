package com.schoolsoft.iam.api;

import com.schoolsoft.enrolment.api.EnrolmentActivity;
import com.schoolsoft.platform.security.Perm;
import com.schoolsoft.platform.tenancy.TenantContext;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Who a family may find in the school directory — the guardian half of GAP-31.
 *
 * <p>{@code directory.view} is in {@code PermissionChecker.GUARDIAN_BASELINE}
 * because a parent needs somebody to address a message to, and
 * {@code /v1/people/directory} narrowed by nothing but {@code school_id}. So
 * one parent's token returned every other parent's and every student's name,
 * email and phone for the whole school — the widest personal-data read in the
 * product, and the one a DPDP complaint would be about.</p>
 *
 * <p>What a family gets instead: <b>staff only</b>, and of those, the ones a
 * parent has a reason to contact — the teachers of the sections their children
 * are enrolled in, plus the office. Other families disappear from the result
 * entirely.</p>
 *
 * <p>"The office" is read from grants rather than role names: a staff member
 * holding {@code guardian.view} is one whose job already includes dealing with
 * families — principal, vice principal, registrar, front office, accountant —
 * while a {@code class_teacher} or {@code subject_teacher} holds no such grant
 * and reaches the directory only by teaching the child. A school that invents
 * a custom parent-facing role gets the right answer by granting it
 * {@code guardian.view}, with no deploy.</p>
 *
 * <p>Staff callers are unrestricted here. The staffroom directory is not the
 * leak — a colleague's extension number is what the screen is for.</p>
 *
 * <p>Both halves of "the teachers of my children's sections" are questions
 * about today, not about a status column: the register is
 * {@link com.schoolsoft.enrolment.api.EnrolmentActivity}'s date predicate, and
 * a timetabled period counts only while it is in force. That is the same
 * window {@link TeacherScope} uses to decide whose sections a teacher may
 * read, and the two have to agree — a parent handed the address of a teacher
 * who cannot open their child's record has been handed a dead one.</p>
 */
@Service
public class DirectoryScope {

    /**
     * The directory a caller may read.
     *
     * @param unrestricted true for staff, chain admins, platform admins and
     *                     jobs: the whole school, as before
     * @param staffIds     when restricted, the only {@code staff.id} values
     *                     that may appear — and no non-staff rows at all. An
     *                     empty list on a restricted scope means an empty
     *                     directory, which is the correct answer for a
     *                     guardian with no enrolled children.
     */
    public record Visible(boolean unrestricted, List<UUID> staffIds) {

        public Visible {
            staffIds = List.copyOf(staffIds);
        }

        static Visible everyone() {
            return new Visible(true, List.of());
        }
    }

    private final DataSource dataSource;
    private final SelfScope selfScope;

    public DirectoryScope(DataSource dataSource, SelfScope selfScope) {
        this.dataSource = dataSource;
        this.selfScope = selfScope;
    }

    public Visible ofCurrentUser() {
        var snap = TenantContext.get();
        if (snap == null) return Visible.everyone();
        if (snap.trusted()) return Visible.everyone();

        boolean family = "guardian".equals(snap.subjectType()) || "student".equals(snap.subjectType());
        if (!family) return Visible.everyone();

        var jdbc = new JdbcTemplate(dataSource);
        List<UUID> office = jdbc.query(
            "SELECT DISTINCT sr.staff_id FROM staff_role sr " +
            "JOIN role_perm rp ON rp.role_code = sr.role_code " +
            "WHERE rp.perm_code = ? AND sr.revoked_at IS NULL",
            (rs, i) -> UUID.fromString(rs.getString(1)), Perm.GUARDIAN_VIEW.code());

        List<UUID> mine = selfScope.ownStudentIds();
        if (mine.isEmpty()) return new Visible(false, office);

        // Both halves are questions about today, and both used to be spelled as
        // something else. The sections are the ones the children are on the
        // register for today — `status = 'active'` flips the day a withdrawal
        // is *filed*, so a family served notice on the 1st for a last day of
        // the 30th lost the school's contact list for the month they most
        // needed it. And a timetabled period counts only while it is in force,
        // so next term's teacher is not contactable yet and last term's has
        // dropped off — which is the same window `TeacherScope` uses to decide
        // whose sections that teacher may read. The two have to agree: a
        // parent who can message a teacher who cannot open their child's
        // record has been given a dead address.
        LocalDate today = LocalDate.now();
        String enrolledToday = EnrolmentActivity.activeOnDateLiteral("e", today);
        String placeholders = String.join(",", Collections.nCopies(mine.size(), "?"));
        List<UUID> teachers = jdbc.query(
            "SELECT DISTINCT sst.teacher_staff_id FROM section_subject_teacher sst " +
            "WHERE sst.section_id IN (SELECT e.section_id FROM enrolment e " +
            "  WHERE " + enrolledToday + " AND e.student_id IN (" + placeholders + ")) " +
            "UNION " +
            "SELECT DISTINCT t.teacher_staff_id FROM timetable_slot t " +
            "WHERE t.teacher_staff_id IS NOT NULL " +
            "  AND t.effective_from <= ? AND COALESCE(t.effective_to, 'infinity'::date) >= ? " +
            "  AND t.section_id IN (SELECT e.section_id FROM enrolment e " +
            "    WHERE " + enrolledToday + " AND e.student_id IN (" + placeholders + "))",
            (rs, i) -> UUID.fromString(rs.getString(1)),
            args(mine, Date.valueOf(today), Date.valueOf(today), mine));

        var all = new java.util.LinkedHashSet<UUID>(office);
        all.addAll(teachers);
        return new Visible(false, List.copyOf(all));
    }

    /** Flattens the mixed list-and-scalar bind arguments, in order. */
    private static Object[] args(Object... parts) {
        var flat = new java.util.ArrayList<Object>();
        for (Object part : parts) {
            if (part instanceof List<?> list) flat.addAll(list);
            else flat.add(part);
        }
        return flat.toArray();
    }
}
