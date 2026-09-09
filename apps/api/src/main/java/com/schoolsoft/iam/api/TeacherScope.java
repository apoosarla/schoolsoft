package com.schoolsoft.iam.api;

import com.schoolsoft.platform.security.Perm;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ForbiddenException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Which sections a teacher is confined to — the third scoping axis, after
 * {@link CampusScope} ("of what campus") and {@link SelfScope} ("whose child").
 *
 * <p>Closes the teaching half of GAP-31 / STF-05. Permissions said a teacher
 * may read a register, a roster or a marks grid; nothing said <em>whose</em>,
 * so a {@code subject_teacher} token could pull any section in the school,
 * including another teacher's marks. {@code AttendanceAuthorizer} guarded the
 * write and deliberately left the read alone rather than do half of this.</p>
 *
 * <h2>Who is confined</h2>
 * Confinement is derived from grants, not from role names, so a school's custom
 * role lands on the right side of the line without a deploy:
 *
 * <blockquote>A staff caller is section-confined when they hold a teaching
 * permission ({@code mark.enter} or {@code attendance.mark}) and do
 * <em>not</em> hold {@code teacher.assign}.</blockquote>
 *
 * <p>{@code teacher.assign} is the marker for standing above the teaching
 * layer: whoever decides who teaches what is not themselves confined to a
 * section. It is held by principal, vice principal, IT admin and academic
 * coordinator, and by no teaching role. A head of school who also takes a class
 * therefore keeps the whole school, which is the answer a school expects.</p>
 *
 * <p>The rule is deliberately shaped so it can only ever narrow a teacher.
 * A registrar, an accountant or a librarian holds no teaching permission, is
 * never confined, and reads exactly what they read before. A teacher with no
 * duties recorded is confined to the empty set rather than promoted to the
 * school — the failure mode of a missing timetable row is a screen with
 * nothing on it, not a leak.</p>
 *
 * <h2>What this is not</h2>
 * It is not a permission check, and it does not narrow plain student lookup:
 * a teacher may still search the school's students and find a guardian's phone
 * number, because a staffroom works that way. It narrows the section-keyed
 * academic reads — the register, the roster, the assessment and its marks.
 *
 * <p>The section set is read from the same facts {@code TeachingDuties} asks
 * about one at a time — {@code section_subject_teacher} for a standing
 * assignment, {@code timetable_slot} for a timetabled period, and
 * {@code timetable_cover} for a period handed over today. The duplication is
 * intentional: that class answers "may this person mark this period", which is
 * a decision about one period, and this one answers "of what", which is a
 * {@code WHERE} clause over many.</p>
 */
@Service
public class TeacherScope {

    /**
     * The caller's section confinement. An {@code unrestricted} scope is not
     * the same as an empty one, and the two must never be conflated — an empty
     * {@code sectionIds} on a confined caller means "no sections", which is a
     * legitimate answer for a teacher between assignments.
     */
    public record Sections(boolean unrestricted, List<UUID> sectionIds) {

        public Sections {
            sectionIds = List.copyOf(sectionIds);
        }

        static Sections unconfined() {
            return new Sections(true, List.of());
        }

        /** Whether {@code sectionId} is inside the scope. */
        public boolean allows(UUID sectionId) {
            return unrestricted || (sectionId != null && sectionIds.contains(sectionId));
        }
    }

    private final DataSource dataSource;
    private final PermissionChecker perms;

    public TeacherScope(DataSource dataSource, PermissionChecker perms) {
        this.dataSource = dataSource;
        this.perms = perms;
    }

    /** The sections the caller is confined to, or an unrestricted scope. */
    public Sections ofCurrentUser() {
        var snap = TenantContext.get();
        if (snap == null) return Sections.unconfined();
        if (snap.trusted() || "platform_admin".equals(snap.subjectType())) return Sections.unconfined();
        if (!"staff".equals(snap.subjectType())) return Sections.unconfined();
        if (!isConfined()) return Sections.unconfined();

        UUID staffId = currentStaffId(snap);
        if (staffId == null) return new Sections(false, List.of());
        return new Sections(false, sectionsTaughtBy(staffId));
    }

    /**
     * Asserts the caller may read section-keyed academic records for
     * {@code sectionId}. A confined caller who does not teach it is refused;
     * everybody else passes through untouched.
     */
    public void requireSection(UUID sectionId) {
        var scope = ofCurrentUser();
        if (scope.unrestricted()) return;
        if (sectionId == null) throw new ForbiddenException("No section named");
        if (scope.allows(sectionId)) return;
        throw new ForbiddenException("You do not teach this section");
    }

    /**
     * Asserts the caller may read section-keyed academic records for
     * {@code studentId} — that the student is currently enrolled in one of the
     * caller's sections. The student-keyed reads a teacher makes (an attendance
     * history, a summary) go through here; {@link SelfScope#requireStudent} is
     * the family's half of the same endpoint and runs first.
     */
    public void requireStudent(UUID studentId) {
        var scope = ofCurrentUser();
        if (scope.unrestricted()) return;
        if (studentId == null) throw new ForbiddenException("No student named");
        if (scope.sectionIds().isEmpty()) throw new ForbiddenException("This student is not in your sections");

        var jdbc = new JdbcTemplate(dataSource);
        String placeholders = String.join(",", Collections.nCopies(scope.sectionIds().size(), "?"));
        java.sql.Date today = java.sql.Date.valueOf(LocalDate.now());
        Object[] args = new Object[scope.sectionIds().size() + 3];
        args[0] = studentId;
        args[1] = today;
        args[2] = today;
        for (int i = 0; i < scope.sectionIds().size(); i++) args[i + 3] = scope.sectionIds().get(i);

        // Only the current enrolment counts. A student the teacher taught last
        // year is not theirs to look through this year, and every student
        // carries a prior-year row. Current is a date, not a status: a child
        // working out their notice is still in the class, and read as a status
        // their own teacher was refused their record the day the withdrawal was
        // filed — while still being expected to mark them present.
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM enrolment e WHERE e.student_id = ? AND "
                + com.schoolsoft.enrolment.api.EnrolmentActivity.activeOn("e") +
            "  AND e.section_id IN (" + placeholders + ")",
            Integer.class, args);
        if (n == null || n == 0) throw new ForbiddenException("This student is not in your sections");
    }

    /**
     * Narrows rows keyed by section to the ones the caller teaches. For the
     * list reads where refusing outright would empty a screen that legitimately
     * spans sections.
     */
    public <T> List<T> narrowToOwnSections(List<T> rows, java.util.function.Function<T, UUID> sectionOf) {
        var scope = ofCurrentUser();
        if (scope.unrestricted()) return rows;
        Set<UUID> mine = Set.copyOf(scope.sectionIds());
        if (mine.isEmpty()) return List.of();
        return rows.stream().filter(r -> mine.contains(sectionOf.apply(r))).toList();
    }

    // ===== internals =====

    private boolean isConfined() {
        boolean teaches = perms.holdsUnrestricted(Perm.MARK_ENTER)
            || perms.holdsUnrestricted(Perm.ATTENDANCE_MARK);
        return teaches && !perms.holdsUnrestricted(Perm.TEACHER_ASSIGN);
    }

    private UUID currentStaffId(TenantContext.Snapshot snap) {
        if (snap.userAccountId() == null) return null;
        var jdbc = new JdbcTemplate(dataSource);
        var rows = jdbc.query(
            "SELECT subject_id FROM user_account WHERE id = ? AND subject_type = 'staff' AND subject_id IS NOT NULL",
            (rs, i) -> UUID.fromString(rs.getString(1)), snap.userAccountId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Every section this staff member has a claim on today: a standing subject
     * assignment, a timetabled period currently in effect, or a cover handed to
     * them for today. Cover is included so a substitute can read the register
     * they have just been told to mark.
     */
    private List<UUID> sectionsTaughtBy(UUID staffId) {
        var jdbc = new JdbcTemplate(dataSource);
        var today = java.sql.Date.valueOf(LocalDate.now());
        return jdbc.query(
            "SELECT section_id FROM section_subject_teacher WHERE teacher_staff_id = ? " +
            "UNION " +
            "SELECT section_id FROM timetable_slot " +
            "  WHERE teacher_staff_id = ? " +
            "    AND effective_from <= ? AND COALESCE(effective_to, 'infinity'::date) >= ? " +
            "UNION " +
            "SELECT t.section_id FROM timetable_cover c JOIN timetable_slot t ON t.id = c.slot_id " +
            "  WHERE c.substitute_staff_id = ? AND c.on_date = ? AND c.cancelled_at IS NULL",
            (rs, i) -> UUID.fromString(rs.getString(1)),
            staffId, staffId, today, today, staffId, today);
    }
}
