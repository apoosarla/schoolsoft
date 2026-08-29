package com.schoolsoft.tenancy.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.platform.tenancy.TenantContext;
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

    public ChainAdminController(ChainProvisioningService provisioningService, JdbcTemplate platformJdbc, DataSource dataSource) {
        this.provisioningService = provisioningService;
        this.platformJdbc = platformJdbc;
        this.dataSource = dataSource;
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
        String schemaName = platformJdbc.query(
            "SELECT schema_name FROM platform.chain WHERE id = ?",
            (rs, i) -> rs.getString("schema_name"), id
        ).stream().findFirst().orElseThrow(() -> new NotFoundException("Chain not found: " + id));

        TenantContext.set(TenantContext.trustedJob(schemaName, id));
        try {
            var chainJdbc = new JdbcTemplate(dataSource);
            long schoolCount = chainJdbc.queryForObject("SELECT count(*) FROM school", Long.class);
            long activeEnrolments = chainJdbc.queryForObject("SELECT count(*) FROM enrolment WHERE status = 'active'", Long.class);
            long staffCount = chainJdbc.queryForObject("SELECT count(*) FROM staff WHERE is_active", Long.class);
            double feeCollectedTotal = chainJdbc.queryForObject("SELECT COALESCE(sum(paid), 0) FROM fee_invoice", Double.class);
            return new ChainStatsDto(id, schoolCount, activeEnrolments, staffCount, feeCollectedTotal);
        } finally {
            TenantContext.clear();
        }
    }
}
