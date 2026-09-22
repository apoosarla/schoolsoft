package com.schoolsoft.tenancy.api;

import com.schoolsoft.audit.api.Audited;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import com.schoolsoft.tenancy.internal.SchoolOnboardingService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opening a school. The checklist that says what is left, and the button that
 * declares it finished.
 *
 * <p>Reading the checklist is {@code structure.view} — the office, the head
 * and the chain's HQ all watch a school being set up. Changing its shape is
 * {@code school.onboard}, which the three heads hold and a chain admin holds
 * as their single write (see {@code PermissionChecker}).</p>
 */
@RestController
@RequestMapping("/v1/tenancy/schools/{id}")
public class SchoolOnboardingController {

    private final SchoolOnboardingService onboarding;

    public SchoolOnboardingController(SchoolOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @PreAuthorize("@perm.can('structure.view')")
    @GetMapping("/readiness")
    public SchoolReadinessDto readiness(@PathVariable UUID id) {
        return onboarding.readiness(id);
    }

    public record SkipStepRequest(String reason) {}

    /**
     * {@code reason} is mandatory and the audit interceptor enforces it before
     * method security runs — a skip with no reason answers 400 about the
     * payload, even from a caller who would have been refused 403.
     */
    @PreAuthorize("@perm.can('school.onboard')")
    @Audited(action = "school.setup_step_skipped", targetType = "school", idParam = "id", snapshot = false)
    @PostMapping("/steps/{stepKey}/skip")
    public SchoolReadinessDto skip(
        @PathVariable UUID id, @PathVariable String stepKey, @RequestBody SkipStepRequest req
    ) {
        return onboarding.skip(id, stepKey, req.reason());
    }

    @PreAuthorize("@perm.can('school.onboard')")
    @Audited(action = "school.setup_step_unskipped", targetType = "school", idParam = "id",
             snapshot = false, requireReason = false)
    @PostMapping("/steps/{stepKey}/unskip")
    public SchoolReadinessDto unskip(@PathVariable UUID id, @PathVariable String stepKey) {
        return onboarding.unskip(id, stepKey);
    }

    public record FirstAdminRequest(
        @NotBlank String firstName,
        String lastName,
        String email,
        String phone,
        String employeeNo,
        String roleCode,
        String campusName
    ) {}

    /**
     * Hands the school to the first person who can run it — the act that was
     * missing between "the chain opened a school" and "the school sets itself
     * up", and the only way {@code ADMIN_ACCOUNT} could ever be ticked without
     * somebody writing SQL.
     *
     * <p>It is {@code school.onboard} and not {@code role.manage} on purpose:
     * granting roles inside a school belongs to the school, and this is not
     * that. It is part of opening one, it names its own school in the path —
     * which is what {@code TenantResolverFilter.CHAIN_ADMIN_PREFIXES} requires
     * of anything a chain admin may call — and the service refuses it the
     * moment the school has anybody. Everyone hired after this one is hired on
     * the school's own screens.</p>
     */
    @PreAuthorize("@perm.can('school.onboard')")
    @Audited(action = "school.first_admin_appointed", targetType = "school", idParam = "id",
             snapshot = false, requireReason = false)
    @PostMapping("/first-admin")
    public SchoolHandoverDto firstAdmin(@PathVariable UUID id, @Valid @RequestBody FirstAdminRequest req) {
        return onboarding.handOver(id, req.firstName(), req.lastName(), req.email(), req.phone(),
            req.employeeNo(), req.roleCode(), req.campusName());
    }

    /**
     * Opens the school. Idempotent by construction: opening one that is
     * already open answers with the same readiness rather than a conflict,
     * because a retry after a dropped response must not report a failure the
     * first attempt did not have.
     */
    @PreAuthorize("@perm.can('school.onboard')")
    @Audited(action = "school.went_live", targetType = "school", idParam = "id", requireReason = false)
    @PostMapping("/go-live")
    public SchoolReadinessDto goLive(@PathVariable UUID id) {
        return onboarding.goLive(id);
    }
}
