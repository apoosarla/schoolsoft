package com.schoolsoft.tenancy.internal;

import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.tenancy.api.OnboardingStepDto;
import com.schoolsoft.tenancy.api.SchoolReadinessDto;
import java.time.Instant;
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

    private final JdbcTemplate jdbc;

    public SchoolOnboardingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
            schoolId, step.key(), reason, actingStaffId());
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

    private Map<String, String> skips(UUID schoolId) {
        Map<String, String> out = new HashMap<>();
        jdbc.query("SELECT step_key, reason FROM school_onboarding_skip WHERE school_id = ?",
            rs -> { out.put(rs.getString("step_key"), rs.getString("reason")); }, schoolId);
        return out;
    }

    /**
     * The staff member behind the token, or null for a chain admin — who is
     * school-less by construction and so has no staff row to name.
     */
    private UUID actingStaffId() {
        var snap = TenantContext.get();
        if (snap == null || snap.userAccountId() == null) return null;
        var rows = jdbc.query(
            "SELECT subject_id FROM user_account WHERE id = ? AND subject_type = 'staff'",
            (rs, i) -> rs.getString("subject_id"), snap.userAccountId());
        return rows.isEmpty() || rows.get(0) == null ? null : UUID.fromString(rows.get(0));
    }
}
