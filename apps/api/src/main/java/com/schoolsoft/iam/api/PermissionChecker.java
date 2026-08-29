package com.schoolsoft.iam.api;

import com.schoolsoft.platform.security.Perm;
import com.schoolsoft.platform.tenancy.TenantContext;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The bean every {@code @PreAuthorize} on this codebase talks to. Registered
 * under the short name {@code perm} so the annotations read
 * {@code @PreAuthorize("@perm.can('fee.invoice.view')")}.
 *
 * <h2>Where a permission comes from</h2>
 * <ul>
 *   <li><b>platform admin</b> and <b>trusted jobs</b> hold everything.</li>
 *   <li><b>staff</b> hold the union of {@code role_perm} rows across every
 *       unrevoked grant in {@code staff_role}. Roles are data, so a school's
 *       custom role works here with no code change.</li>
 *   <li><b>guardians</b> and <b>students</b> hold a fixed built-in set — see
 *       {@link #GUARDIAN_BASELINE} / {@link #STUDENT_BASELINE}. They have no
 *       {@code staff_role} rows, and giving them editable ones would let a
 *       school hand a parent the fee ledger by mistake.</li>
 * </ul>
 *
 * <h2>What a permission is not</h2>
 * A permission answers "may this caller use this endpoint at all". It does not
 * answer "for this student", "for this section" or "for this campus" — those
 * stay with {@link SelfScope}, {@link Authz#campusScopeOfCurrentUser()} and the
 * per-module authorizers, which run after the gate has let the call through.
 *
 * <h2>Caching</h2>
 * Resolution is one query, memoised into the current request's attributes. A
 * call outside a request (a job, a test) resolves fresh every time, which is
 * correct and rare enough not to matter.
 */
@Component("perm")
public class PermissionChecker {

    private static final String CACHE_KEY = PermissionChecker.class.getName() + ".perms";

    /**
     * What a guardian may do without any role grant: read their own children's
     * school life, talk to the school, and apply for leave on their behalf.
     * Every entry is either self-scoped or harmless school-wide (announcements
     * are published to them by definition).
     */
    static final Set<Perm> GUARDIAN_BASELINE = EnumSet.of(
        Perm.STUDENT_VIEW_OWN,
        Perm.ENROLMENT_VIEW_OWN,
        Perm.ATTENDANCE_VIEW_OWN,
        Perm.TIMETABLE_VIEW_OWN,
        Perm.MARK_VIEW_OWN,
        Perm.REPORT_CARD_VIEW_OWN,
        Perm.EXAM_VIEW_OWN,
        Perm.HALL_TICKET_VIEW_OWN,
        Perm.FEE_INVOICE_VIEW_OWN,
        Perm.LEAVE_APPLY,
        Perm.ANNOUNCEMENT_VIEW,
        Perm.MESSAGE_PARTICIPATE,
        Perm.LMS_CONTENT_VIEW,
        Perm.MARK_REEVAL_REQUEST,
        Perm.DIRECTORY_VIEW,
        Perm.TRANSPORT_TRACK,
        Perm.STRUCTURE_VIEW
    );

    /** A student sees their own, and submits their own work. */
    static final Set<Perm> STUDENT_BASELINE = EnumSet.of(
        Perm.STUDENT_VIEW_OWN,
        Perm.ENROLMENT_VIEW_OWN,
        Perm.ATTENDANCE_VIEW_OWN,
        Perm.TIMETABLE_VIEW_OWN,
        Perm.MARK_VIEW_OWN,
        Perm.REPORT_CARD_VIEW_OWN,
        Perm.EXAM_VIEW_OWN,
        Perm.HALL_TICKET_VIEW_OWN,
        Perm.ANNOUNCEMENT_VIEW,
        Perm.MESSAGE_PARTICIPATE,
        Perm.LMS_CONTENT_VIEW,
        Perm.LMS_SUBMIT,
        Perm.LIBRARY_VIEW,
        Perm.STRUCTURE_VIEW
    );

    /**
     * A chain (HQ) admin oversees every school in the chain: they read
     * anything and change nothing. Derived from {@link Perm#isUnrestrictedRead()}
     * so a permission added later lands on the right side of that line without
     * anybody remembering to come back here.
     */
    static final Set<Perm> CHAIN_ADMIN_BASELINE = EnumSet.copyOf(
        java.util.Arrays.stream(Perm.values()).filter(Perm::isUnrestrictedRead).toList());

    private final DataSource dataSource;

    public PermissionChecker(DataSource dataSource) { this.dataSource = dataSource; }

    // ===== the SpEL surface =====

    /** True when the caller holds {@code code}. Unknown codes are always false. */
    public boolean can(String code) {
        var perm = Perm.byCode(code);
        return perm.isPresent() && currentPerms().contains(perm.get());
    }

    /** True when the caller holds any one of {@code codes}. */
    public boolean canAny(String... codes) {
        for (String code : codes) {
            if (can(code)) return true;
        }
        return false;
    }

    /**
     * The gate for an endpoint that serves both the office and the family:
     * {@code staffCode} is the unrestricted permission, {@code ownCode} the
     * self-scoped one. Passing on {@code ownCode} alone obliges the handler to
     * narrow the query to the caller — {@link SelfScope} is how.
     */
    public boolean canAnyOf(String staffCode, String ownCode) {
        return can(staffCode) || can(ownCode);
    }

    /** True when the caller holds the unrestricted code, not merely its {@code .own} sibling. */
    public boolean holdsUnrestricted(Perm unrestricted) {
        return currentPerms().contains(unrestricted);
    }

    // ===== resolution =====

    public Set<Perm> currentPerms() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) return resolve();

        @SuppressWarnings("unchecked")
        Set<Perm> cached = (Set<Perm>) attrs.getAttribute(CACHE_KEY, RequestAttributes.SCOPE_REQUEST);
        if (cached != null) return cached;

        Set<Perm> resolved = resolve();
        attrs.setAttribute(CACHE_KEY, resolved, RequestAttributes.SCOPE_REQUEST);
        return resolved;
    }

    private Set<Perm> resolve() {
        var snap = TenantContext.get();
        if (snap == null) return EnumSet.noneOf(Perm.class);
        if (snap.trusted() || "platform_admin".equals(snap.subjectType())) return EnumSet.allOf(Perm.class);

        return switch (snap.subjectType()) {
            case "guardian"    -> GUARDIAN_BASELINE;
            case "student"     -> STUDENT_BASELINE;
            case "chain_admin" -> CHAIN_ADMIN_BASELINE;
            case "staff"       -> staffPerms(snap.userAccountId());
            default            -> EnumSet.noneOf(Perm.class);
        };
    }

    private Set<Perm> staffPerms(java.util.UUID userAccountId) {
        if (userAccountId == null) return EnumSet.noneOf(Perm.class);
        var jdbc = new JdbcTemplate(dataSource);
        List<String> codes = jdbc.queryForList(
            "SELECT DISTINCT rp.perm_code FROM role_perm rp " +
            "JOIN staff_role sr ON sr.role_code = rp.role_code " +
            "JOIN user_account ua ON ua.subject_id = sr.staff_id AND ua.subject_type = 'staff' " +
            "WHERE ua.id = ? AND sr.revoked_at IS NULL",
            String.class, userAccountId);

        var out = EnumSet.noneOf(Perm.class);
        // A code the database knows and this build does not is a permission
        // that was removed from the enum: ignore it rather than fail the
        // request, and let the migration that removed it clean the rows up.
        for (String code : codes) {
            Perm.byCode(code).ifPresent(out::add);
        }
        return out;
    }
}
