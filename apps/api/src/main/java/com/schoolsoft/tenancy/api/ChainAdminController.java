package com.schoolsoft.tenancy.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.tenancy.internal.ChainHandoverService;
import com.schoolsoft.tenancy.internal.SchoolOnboardingService;
import com.schoolsoft.tenancy.internal.SchoolRepository;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.platform.web.NotFoundException;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-admin only. Onboards a new chain (tenant) and lists existing ones —
 * the API half of the Chain HQ Console's tenant/school onboarding flow
 * (design doc §15; tracked in BACKLOG.md before this landed).
 *
 * Every endpoint requires {@code subjectType == 'platform_admin'} on the
 * resolved {@link TenantContext}. There is no per-chain scoping here by
 * design — this controller operates above any single chain, against the
 * shared {@code platform} schema, never a {@code chain_X} schema.
 */
@RestController
@RequestMapping("/v1/platform-admin/chains")
public class ChainAdminController {

    private final ChainProvisioningService provisioningService;
    private final JdbcTemplate platformJdbc;
    private final DataSource dataSource;
    private final SchoolRepository schools;
    private final SchoolOnboardingService onboarding;
    private final ChainHandoverService handover;

    public ChainAdminController(ChainProvisioningService provisioningService, JdbcTemplate platformJdbc,
                                DataSource dataSource, SchoolRepository schools,
                                SchoolOnboardingService onboarding, ChainHandoverService handover) {
        this.provisioningService = provisioningService;
        this.platformJdbc = platformJdbc;
        this.dataSource = dataSource;
        this.schools = schools;
        this.onboarding = onboarding;
        this.handover = handover;
    }

    private void requirePlatformAdmin() {
        var snap = TenantContext.get();
        if (snap == null || !"platform_admin".equals(snap.subjectType())) {
            throw new ForbiddenException("platform_admin role required");
        }
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping
    public List<ChainDto> list() {
        requirePlatformAdmin();
        return platformJdbc.query(
            "SELECT id, slug, name, schema_name, plan_code, region, status, schema_version, created_at " +
            "FROM platform.chain ORDER BY created_at DESC",
            (rs, i) -> new ChainDto(
                UUID.fromString(rs.getString("id")),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("schema_name"),
                rs.getString("plan_code"),
                rs.getString("region"),
                rs.getString("status"),
                rs.getInt("schema_version"),
                rs.getTimestamp("created_at").toInstant()
            )
        );
    }

    public record ProvisionChainRequest(
        @NotBlank String slug,
        @NotBlank String name,
        String planCode
    ) {}

    public record ProvisionChainResponse(UUID chainId, String schemaName, boolean created) {}

    /**
     * Idempotent on slug — see {@link ChainProvisioningService#provision}.
     * Safe for the HQ Console to retry on a flaky network without risking a
     * duplicate chain.
     */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PostMapping
    public ResponseEntity<ProvisionChainResponse> provision(@RequestBody ProvisionChainRequest req) {
        requirePlatformAdmin();
        var result = provisioningService.provision(req.slug(), req.name(), req.planCode());
        return ResponseEntity.ok(new ProvisionChainResponse(result.id(), result.schemaName(), result.created()));
    }

    /**
     * Cross-chain platform-admin view. Per Risk R12 ("Cross-chain analytics
     * rebuilt out of OLTP") — the sanctioned MVP posture is a small fan-out
     * query helper against a single chain schema, not a warehouse (that's
     * Phase 2). Uses {@link TenantContext#trustedJob} the same way
     * {@code UserLookupService} and {@code ChainSchemaMigrator} already do
     * to step outside the requesting platform-admin's own search_path.
     */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/{id}/stats")
    public ChainStatsDto stats(@PathVariable UUID id) {
        requirePlatformAdmin();
        return inChain(id, chainJdbc -> {
            long schoolCount = chainJdbc.queryForObject("SELECT count(*) FROM school", Long.class);
            // Children on a register today across the chain — the date
            // predicate, not the status, so a filed withdrawal does not drop
            // the headcount before the child has left.
            java.sql.Date today = java.sql.Date.valueOf(java.time.LocalDate.now());
            long activeEnrolments = chainJdbc.queryForObject(
                "SELECT count(*) FROM enrolment e WHERE "
                    + com.schoolsoft.enrolment.api.EnrolmentActivity.activeOn("e"),
                Long.class, today, today);
            long staffCount = chainJdbc.queryForObject("SELECT count(*) FROM staff WHERE is_active", Long.class);
            double feeCollectedTotal = chainJdbc.queryForObject("SELECT COALESCE(sum(paid), 0) FROM fee_invoice", Double.class);
            return new ChainStatsDto(id, schoolCount, activeEnrolments, staffCount, feeCollectedTotal);
        });
    }

    // ----------------------------------------------------- a chain's schools

    /**
     * The schools inside one chain, for the console that opens them. The
     * headcount is the date predicate rather than the status, for the reason
     * {@link com.schoolsoft.enrolment.api.EnrolmentActivity} exists.
     */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/{id}/schools")
    public List<ChainSchoolDto> schools(@PathVariable UUID id) {
        requirePlatformAdmin();
        java.sql.Date today = java.sql.Date.valueOf(java.time.LocalDate.now());
        return inChain(id, chainJdbc -> chainJdbc.query(
            "SELECT s.id, s.slug, s.name, s.board_code, s.lifecycle, s.went_live_at, "
                + "  (SELECT count(*) FROM enrolment e WHERE e.school_id = s.id AND "
                + com.schoolsoft.enrolment.api.EnrolmentActivity.activeOn("e") + ") AS active_enrolments "
                + "FROM school s ORDER BY s.name",
            (rs, i) -> new ChainSchoolDto(
                UUID.fromString(rs.getString("id")),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("board_code"),
                rs.getString("lifecycle"),
                rs.getTimestamp("went_live_at") == null ? null : rs.getTimestamp("went_live_at").toInstant(),
                rs.getLong("active_enrolments")),
            today, today));
    }

    /**
     * Opens a school in a chain the operator does not belong to. The same act
     * a chain's own HQ admin performs against {@code /v1/tenancy/schools};
     * this is the door for the operator doing it on their behalf, because a
     * platform-admin token carries the platform schema and cannot reach a
     * chain's tables without stepping into one.
     */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PostMapping("/{id}/schools")
    public SchoolDto createSchool(@PathVariable UUID id, @RequestBody SchoolController.CreateSchoolRequest req) {
        requirePlatformAdmin();
        return inChain(id, chainJdbc -> schools.create(
            req.slug(), req.name(), req.boardCode(), req.gstin(), req.stateCode()));
    }

    /** One school's setup checklist, asked from outside its chain. */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/{id}/schools/{schoolId}/readiness")
    public SchoolReadinessDto schoolReadiness(@PathVariable UUID id, @PathVariable UUID schoolId) {
        requirePlatformAdmin();
        return inChain(id, chainJdbc -> onboarding.readiness(schoolId));
    }

    // -------------------------------------------------- handing a chain over

    public record AppointChainAdminRequest(String email, String phone) {}

    /**
     * Who runs this chain, if anybody yet. Empty is the state a chain is in
     * between being provisioned and being handed over, and it is the state
     * the console has to be able to see: schools can be opened in such a
     * chain, but none of them can leave {@code draft}, because appointing a
     * school's first administrator is the chain admin's act and there is no
     * chain admin to perform it.
     */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/{id}/admins")
    public List<ChainAdminDto> admins(@PathVariable UUID id) {
        requirePlatformAdmin();
        return inChain(id, chainJdbc -> handover.admins());
    }

    /**
     * Hands the chain to the customer: creates the one {@code chain_admin}
     * account that everything else inside the chain descends from.
     *
     * <p>This is the operator's only write of a person into a customer's
     * chain, and it exists because nothing inside a chain could do it — a
     * chain's every door is a {@code user_account} in it, so a chain with no
     * accounts has no door. Its schools are the vendor's to create and the
     * customer's to open; see {@link ChainHandoverService}.</p>
     *
     * <p>Not {@code @Audited}: that interceptor is a web interceptor and runs
     * while this request still stands in the {@code platform} schema, which
     * holds no {@code audit_log}. The service writes the row itself, inside
     * the customer's chain, where the customer can read it.</p>
     */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PostMapping("/{id}/admins")
    public ChainAdminDto appointAdmin(@PathVariable UUID id, @RequestBody AppointChainAdminRequest req) {
        requirePlatformAdmin();
        String operator = operatorLabel();
        return inChain(id, chainJdbc -> handover.appoint(req.email(), req.phone(), operator));
    }

    /** The operator's own address, for the audit row the customer will read. */
    private String operatorLabel() {
        var snap = TenantContext.get();
        UUID operatorId = snap == null ? null : snap.userAccountId();
        if (operatorId == null) return "unknown";
        return platformJdbc.query("SELECT email FROM platform.platform_user WHERE id = ?",
            (rs, i) -> rs.getString("email"), operatorId)
            .stream().findFirst().orElse(operatorId.toString());
    }

    /**
     * Runs {@code body} inside one chain's schema as a trusted job — the same
     * step {@code UserLookupService} and {@code ChainSchemaMigrator} take, and
     * the only way a platform-admin token reaches a chain's tables: its own
     * claims name the platform schema.
     *
     * <p>Trusted means RLS is bypassed, so everything here is cross-school by
     * construction. That is the point of this controller and the reason every
     * method on it is platform-admin only.</p>
     */
    private <T> T inChain(UUID chainId, java.util.function.Function<JdbcTemplate, T> body) {
        String schemaName = platformJdbc.query(
            "SELECT schema_name FROM platform.chain WHERE id = ?",
            (rs, i) -> rs.getString("schema_name"), chainId
        ).stream().findFirst().orElseThrow(() -> new NotFoundException("Chain not found: " + chainId));

        TenantContext.set(TenantContext.trustedJob(schemaName, chainId));
        try {
            return body.apply(new JdbcTemplate(dataSource));
        } finally {
            TenantContext.clear();
        }
    }
}
