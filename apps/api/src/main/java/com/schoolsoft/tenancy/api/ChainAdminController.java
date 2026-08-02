package com.schoolsoft.tenancy.api;

import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ForbiddenException;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
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

    public ChainAdminController(ChainProvisioningService provisioningService, JdbcTemplate platformJdbc) {
        this.provisioningService = provisioningService;
        this.platformJdbc = platformJdbc;
    }

    private void requirePlatformAdmin() {
        var snap = TenantContext.get();
        if (snap == null || !"platform_admin".equals(snap.subjectType())) {
            throw new ForbiddenException("platform_admin role required");
        }
    }

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
    @PostMapping
    public ResponseEntity<ProvisionChainResponse> provision(@RequestBody ProvisionChainRequest req) {
        requirePlatformAdmin();
        var result = provisioningService.provision(req.slug(), req.name(), req.planCode());
        return ResponseEntity.ok(new ProvisionChainResponse(result.id(), result.schemaName(), result.created()));
    }
}
