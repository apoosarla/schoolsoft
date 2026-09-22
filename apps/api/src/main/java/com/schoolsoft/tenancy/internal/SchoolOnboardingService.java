package com.schoolsoft.tenancy.internal;

import com.schoolsoft.iam.api.AccountProvisioning;
import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.people.api.StaffDto;
import com.schoolsoft.people.api.StaffOnboarding;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.OnboardingStepDto;
import com.schoolsoft.tenancy.api.SchoolHandoverDto;
import com.schoolsoft.tenancy.api.SchoolReadinessDto;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening a school: what is left to do, and the act of declaring it done.
 *
 * <p>Readiness is asked, never stored — each step in {@link OnboardingStep}
 * counts the rows that would exist if it were finished. The only state kept
 * here is what those rows cannot say: which optional step a school decided
 * does not apply to it (and why), and the moment it opened.</p>
 */
@Service
public class SchoolOnboardingService {

    /** The permission that makes somebody able to run a school, not the name of a role. */
    private static final String RUNS_THE_SCHOOL = "structure.manage";

    /** What a chain gets if it names no role: the head of school. */
    private static final String DEFAULT_ROLE = "principal";

    private final JdbcTemplate jdbc;
    private final Authz authz;
    private final SchoolRepository schools;
    private final StaffOnboarding staff;
    private final AccountProvisioning accounts;

    public SchoolOnboardingService(JdbcTemplate jdbc, Authz authz, SchoolRepository schools,
                                   StaffOnboarding staff, AccountProvisioning accounts) {
        this.jdbc = jdbc;
        this.authz = authz;
        this.schools = schools;
        this.staff = staff;
        this.accounts = accounts;
    }

    // ----------------------------------------------------------- readiness

    /**
     * The checklist. A school this caller cannot see is a 404 rather than an
     * empty checklist — RLS makes another school's id invisible, and answering
     * "nothing is done" about a school that is not yours would be a lie about
     * somebody else's school.
     */
    public SchoolReadinessDto readiness(UUID schoolId) {
        var school = school(schoolId);
        Map<String, String> skips = skips(schoolId);

        List<OnboardingStepDto> steps = new ArrayList<>();
        for (OnboardingStep step : OnboardingStep.values()) {
            long count = jdbc.queryForObject(step.probe(), Long.class, schoolId);
            String skipReason = skips.get(step.key());
            steps.add(new OnboardingStepDto(
                step.key(), step.label(), step.why(), step.blocking(),
                count > 0, count, step.unit(count), skipReason != null, skipReason));
        }

        return new SchoolReadinessDto(
            schoolId, school.lifecycle(), school.wentLiveAt(), openBlockingSteps(steps).isEmpty(), steps);
    }

    private static List<OnboardingStepDto> openBlockingSteps(List<OnboardingStepDto> steps) {
        return steps.stream().filter(s -> s.blocking() && !s.done()).toList();
    }

    // ---------------------------------------------------------------- skip

    /**
     * Records that a school is not going to do an optional step. A blocking
     * step is refused: a school with no sections cannot open whatever reason
     * it offers, and storing a waiver that changes nothing would make the
     * refusal at go-live look arbitrary.
     */
    @Transactional
    public SchoolReadinessDto skip(UUID schoolId, String stepKey, String reason) {
        school(schoolId);
        OnboardingStep step = OnboardingStep.byKey(stepKey)
            .orElseThrow(() -> new IllegalArgumentException("No setup step called '" + stepKey + "'"));
        if (step.blocking()) {
            throw new ConflictException(
                "'" + step.label() + "' cannot be skipped — a school cannot open without it. " + step.why());
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Skipping a setup step needs a reason");
        }
        jdbc.update(
            "INSERT INTO school_onboarding_skip (school_id, step_key, reason, skipped_by_staff_id) " +
            "VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (school_id, step_key) DO UPDATE SET reason = EXCLUDED.reason, " +
            "  skipped_by_staff_id = EXCLUDED.skipped_by_staff_id, skipped_at = now()",
            schoolId, step.key(), reason, authz.currentStaffId());
        return readiness(schoolId);
    }

    /** Puts a skipped step back on the list. Skipping what was never skipped is not an error. */
    @Transactional
    public SchoolReadinessDto unskip(UUID schoolId, String stepKey) {
        school(schoolId);
        OnboardingStep step = OnboardingStep.byKey(stepKey)
            .orElseThrow(() -> new IllegalArgumentException("No setup step called '" + stepKey + "'"));
        jdbc.update("DELETE FROM school_onboarding_skip WHERE school_id = ? AND step_key = ?",
            schoolId, step.key());
        return readiness(schoolId);
    }

    // ------------------------------------------------------------- go live

    /**
     * Opens the school. One conditional UPDATE naming the state it moves out
     * of, so two people pressing the button at the same moment cannot both
     * believe they opened it.
     *
     * <p>Zero rows is not automatically a failure: a school that is already
     * live was opened by the first attempt, and a retry must not fail because
     * the first one worked. A suspended school is a real conflict, and says
     * so.</p>
     */
    @Transactional
    public SchoolReadinessDto goLive(UUID schoolId) {
        var readiness = readiness(schoolId);
        var open = openBlockingSteps(readiness.steps());
        if (!open.isEmpty() && !"live".equals(readiness.lifecycle())) {
            throw new ConflictException(
                "This school is not ready to open — " + open.size()
                + (open.size() == 1 ? " step is" : " steps are") + " still open: "
                // Names the steps and not their reasons: the checklist carries
                // the "why" beside each one, and repeating all of it here
                // buries the list the caller has to act on.
                + String.join(", ", open.stream().map(OnboardingStepDto::label).toList()) + ".");
        }

        int moved = jdbc.update(
            "UPDATE school SET lifecycle = 'live', went_live_at = COALESCE(went_live_at, now()), " +
            "  updated_at = now() WHERE id = ? AND lifecycle = 'draft'", schoolId);
        if (moved == 0) {
            String lifecycle = school(schoolId).lifecycle();
            if (!"live".equals(lifecycle)) {
                throw new ConflictException(
                    "Cannot open a school that is '" + lifecycle
                    + "' — the transition starts from draft.");
            }
        }
        return readiness(schoolId);
    }

    // ------------------------------------------------------------ handover

    /**
     * Hands a newly opened school to the first person who can run it.
     *
     * <h2>Why this exists at all</h2>
     * Every other account in a school is created by somebody already inside
     * it — the office admits a student, the head grants a role. The first one
     * cannot be: a school opened by its chain has nobody in it, so
     * {@code ADMIN_ACCOUNT} was a checklist step no screen could ever tick and
     * every environment answered with hand-written SQL. This is that act,
     * named, audited and refused once it has happened.
     *
     * <h2>Why it also creates a campus</h2>
     * {@code staff.campus_id} is NOT NULL — a person works somewhere — so a
     * school with no campus cannot hold a staff row at all. Creating the
     * school's first campus here is not scope creep but the same act: the
     * alternative is a chain admin who can appoint a head only after somebody
     * who does not exist yet has made a campus. An existing campus is used as
     * it stands and nothing is created.
     *
     * <h2>Once only</h2>
     * The guard is {@code ADMIN_ACCOUNT}'s own probe, so the question asked is
     * the checklist's: has this school anybody holding
     * {@code structure.manage}. Once it has, this door is closed and the rest
     * of the school's staff are hired on the school's own screens by the
     * person who came through it.
     */
    @Transactional
    public SchoolHandoverDto handOver(UUID schoolId, String firstName, String lastName, String email,
                                      String phone, String employeeNo, String roleCode, String campusName) {
        school(schoolId);

        long alreadyThere = jdbc.queryForObject(OnboardingStep.ADMIN_ACCOUNT.probe(), Long.class, schoolId);
        if (alreadyThere > 0) {
            throw new ConflictException(
                "This school already has somebody who can run it. Further staff are added at the school, "
                + "by the person who holds it — this door opens once.");
        }

        String role = roleCode == null || roleCode.isBlank() ? DEFAULT_ROLE : roleCode.trim();
        if (!accounts.roleHolds(role, RUNS_THE_SCHOOL)) {
            throw new ConflictException(
                "The role '" + role + "' does not carry " + RUNS_THE_SCHOOL + ", so somebody holding it "
                + "could not set the school up. Name a role that does.");
        }

        List<UUID> existingCampus = jdbc.queryForList(
            "SELECT id FROM campus WHERE school_id = ? ORDER BY is_primary DESC, name LIMIT 1",
            UUID.class, schoolId);
        boolean campusCreated = existingCampus.isEmpty();
        UUID campusId = campusCreated
            ? schools.createCampus(schoolId,
                campusName == null || campusName.isBlank() ? "Main Campus" : campusName.trim(), true).id()
            : existingCampus.get(0);

        StaffDto created = staff.create(new StaffOnboarding.NewStaff(
            schoolId, campusId,
            employeeNo == null || employeeNo.isBlank() ? "EMP-001" : employeeNo.trim(),
            firstName, lastName, blankToNull(email), blankToNull(phone),
            "permanent", LocalDate.now()));

        UUID accountId = accounts.createStaffAccount(schoolId, created.id(), email, phone);
        accounts.grantSchoolRole(created.id(), schoolId, role);

        return new SchoolHandoverDto(
            schoolId, created, accountId, campusId, campusCreated, role,
            created.email() == null ? created.phone() : created.email(),
            readiness(schoolId));
    }

    // ------------------------------------------------------------- helpers

    private record SchoolState(String lifecycle, Instant wentLiveAt) {}

    private SchoolState school(UUID schoolId) {
        var rows = jdbc.query(
            "SELECT lifecycle, went_live_at FROM school WHERE id = ?",
            (rs, i) -> new SchoolState(
                rs.getString("lifecycle"),
                rs.getTimestamp("went_live_at") == null ? null : rs.getTimestamp("went_live_at").toInstant()),
            schoolId);
        if (rows.isEmpty()) throw new NotFoundException("School not found: " + schoolId);
        return rows.get(0);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Map<String, String> skips(UUID schoolId) {
        Map<String, String> out = new HashMap<>();
        jdbc.query("SELECT step_key, reason FROM school_onboarding_skip WHERE school_id = ?",
            rs -> { out.put(rs.getString("step_key"), rs.getString("reason")); }, schoolId);
        return out;
    }

}
