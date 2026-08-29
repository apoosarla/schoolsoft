package com.schoolsoft.iam.api;

import com.schoolsoft.platform.security.Perm;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ForbiddenException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The second half of a {@code .own} permission.
 *
 * {@link PermissionChecker} lets a guardian through the door of
 * {@code /v1/fees/students/{id}/dues}; this decides whether {@code id} is one
 * of <em>their</em> children. Skipping it turns a self-service endpoint into a
 * directory of every family's money, which is exactly the shape of bug a
 * permission check alone cannot catch — so any handler gated on
 * {@code canAnyOf(staffCode, ownCode)} must call
 * {@link #requireStudent(UUID, Perm)} before it reads anything.
 *
 * <p>A caller holding the unrestricted permission passes straight through: the
 * office is not restricted to its own children, and asking it to be would make
 * every staff read a two-query affair.</p>
 */
@Service
public class SelfScope {

    private final DataSource dataSource;
    private final PermissionChecker perms;

    public SelfScope(DataSource dataSource, PermissionChecker perms) {
        this.dataSource = dataSource;
        this.perms = perms;
    }

    /**
     * Asserts the caller may see {@code studentId}: either they hold
     * {@code unrestricted}, or the student is their own / their child.
     *
     * @param unrestricted the school-wide sibling of the {@code .own} permission
     *                     the endpoint also accepts
     */
    public void requireStudent(UUID studentId, Perm unrestricted) {
        // Unrestricted first: a caller who may read any student may also read a
        // row with no single student behind it (a combined family invoice), and
        // must not be refused for a null the check itself introduced.
        if (perms.holdsUnrestricted(unrestricted)) return;
        if (studentId == null) throw new ForbiddenException("No student named");

        var snap = TenantContext.get();
        if (snap == null) throw new ForbiddenException("No authenticated caller");

        if (isOwnStudent(snap, studentId)) return;
        throw new ForbiddenException("This student is not yours to view");
    }

    /**
     * The students the caller may see when they hold only the {@code .own}
     * permission — their own row for a student, their children for a guardian,
     * empty for anyone else. Callers holding the unrestricted permission
     * should not narrow at all; {@link #requireStudent} is the guard for that.
     */
    public List<UUID> ownStudentIds() {
        var snap = TenantContext.get();
        if (snap == null || snap.userAccountId() == null) return List.of();
        var jdbc = new JdbcTemplate(dataSource);

        return switch (snap.subjectType()) {
            case "student" -> jdbc.query(
                "SELECT subject_id FROM user_account WHERE id = ? AND subject_type = 'student' AND subject_id IS NOT NULL",
                (rs, i) -> UUID.fromString(rs.getString(1)), snap.userAccountId());
            case "guardian" -> jdbc.query(
                "SELECT gs.student_id FROM guardian_student gs " +
                "JOIN user_account ua ON ua.subject_id = gs.guardian_id AND ua.subject_type = 'guardian' " +
                "WHERE ua.id = ?",
                (rs, i) -> UUID.fromString(rs.getString(1)), snap.userAccountId());
            default -> List.of();
        };
    }

    /**
     * Narrows a list to the caller's own students, unless they hold
     * {@code unrestricted}.
     *
     * <p>For the reads keyed by something other than a student — a component's
     * marks, a section's roster — where refusing outright would break the
     * family apps but returning the whole list would hand a parent the class.
     * The caller sees their own rows and learns nothing about the rest.</p>
     */
    public <T> List<T> narrowToOwnStudents(List<T> rows, java.util.function.Function<T, UUID> studentOf,
                                           Perm unrestricted) {
        if (perms.holdsUnrestricted(unrestricted)) return rows;
        var mine = Set.copyOf(ownStudentIds());
        if (mine.isEmpty()) return List.of();
        return rows.stream().filter(r -> mine.contains(studentOf.apply(r))).toList();
    }

    /**
     * Asserts the caller is the guardian {@code guardianId}, unless they hold
     * {@code unrestricted}. Guards the endpoints keyed by guardian rather than
     * by student.
     */
    public void requireGuardian(UUID guardianId, Perm unrestricted) {
        if (guardianId == null) throw new ForbiddenException("No guardian named");
        if (perms.holdsUnrestricted(unrestricted)) return;

        var snap = TenantContext.get();
        if (snap == null || !"guardian".equals(snap.subjectType())) {
            throw new ForbiddenException("This guardian is not yours to view");
        }
        var jdbc = new JdbcTemplate(dataSource);
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM user_account WHERE id = ? AND subject_type = 'guardian' AND subject_id = ?",
            Integer.class, snap.userAccountId(), guardianId);
        if (n == null || n == 0) throw new ForbiddenException("This guardian is not yours to view");
    }

    /**
     * Asserts the caller is the staff member {@code staffId}, unless they hold
     * {@code unrestricted}. Guards "my timetable" style endpoints, where a
     * teacher may read their own duties without holding the school-wide view.
     */
    public void requireStaff(UUID staffId, Perm unrestricted) {
        if (staffId == null) throw new ForbiddenException("No staff member named");
        if (perms.holdsUnrestricted(unrestricted)) return;

        var snap = TenantContext.get();
        if (snap == null || !"staff".equals(snap.subjectType())) {
            throw new ForbiddenException("These duties are not yours to view");
        }
        var jdbc = new JdbcTemplate(dataSource);
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM user_account WHERE id = ? AND subject_type = 'staff' AND subject_id = ?",
            Integer.class, snap.userAccountId(), staffId);
        if (n == null || n == 0) throw new ForbiddenException("These duties are not yours to view");
    }

    /**
     * Asserts the caller has a stake in {@code sectionId}: a student enrolled
     * in it, a guardian of one, or a staff member holding {@code unrestricted}.
     * Guards the section-keyed reads a family app makes — a class timetable, a
     * day's periods — which would otherwise hand any parent every section in
     * the school.
     */
    public void requireSection(UUID sectionId, Perm unrestricted) {
        if (sectionId == null) throw new ForbiddenException("No section named");
        if (perms.holdsUnrestricted(unrestricted)) return;

        List<UUID> mine = ownStudentIds();
        if (mine.isEmpty()) throw new ForbiddenException("This section is not yours to view");

        var jdbc = new JdbcTemplate(dataSource);
        String placeholders = String.join(",", java.util.Collections.nCopies(mine.size(), "?"));
        Object[] args = new Object[mine.size() + 1];
        args[0] = sectionId;
        for (int i = 0; i < mine.size(); i++) args[i + 1] = mine.get(i);

        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM enrolment WHERE section_id = ? AND student_id IN (" + placeholders + ")",
            Integer.class, args);
        if (n == null || n == 0) throw new ForbiddenException("This section is not yours to view");
    }

    /** The user account behind the caller, for endpoints keyed by it (message threads). */
    public void requireUserAccount(UUID userAccountId) {
        var snap = TenantContext.get();
        if (snap == null) throw new ForbiddenException("No authenticated caller");
        if (snap.trusted() || "platform_admin".equals(snap.subjectType())) return;
        if (!snap.userAccountId().equals(userAccountId)) {
            throw new ForbiddenException("That is somebody else's account");
        }
    }

    private boolean isOwnStudent(TenantContext.Snapshot snap, UUID studentId) {
        var jdbc = new JdbcTemplate(dataSource);
        return switch (snap.subjectType()) {
            case "student" -> {
                Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM user_account WHERE id = ? AND subject_type = 'student' AND subject_id = ?",
                    Integer.class, snap.userAccountId(), studentId);
                yield n != null && n > 0;
            }
            case "guardian" -> {
                Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM guardian_student gs " +
                    "JOIN user_account ua ON ua.subject_id = gs.guardian_id AND ua.subject_type = 'guardian' " +
                    "WHERE ua.id = ? AND gs.student_id = ?",
                    Integer.class, snap.userAccountId(), studentId);
                yield n != null && n > 0;
            }
            default -> false;
        };
    }
}
