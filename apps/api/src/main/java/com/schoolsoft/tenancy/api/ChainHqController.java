package com.schoolsoft.tenancy.api;

import com.schoolsoft.audit.api.Audited;
import com.schoolsoft.tenancy.internal.ChainHqService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The chain's own HQ accounts, managed by the HQ itself.
 *
 * <p>Chain-wide by construction — an HQ account belongs to no school — which
 * is what lets it sit under {@code TenantResolverFilter.CHAIN_ADMIN_PREFIXES}.
 * The operator's one appointment is {@code ChainAdminController}'s; everyone
 * after it is added here.</p>
 *
 * <p>Reading the list is gated on the same permission as changing it: nobody
 * but the HQ has a reason to see who else signs in as the HQ.</p>
 */
@RestController
@RequestMapping("/v1/tenancy/chain/admins")
public class ChainHqController {

    private final ChainHqService hq;

    public ChainHqController(ChainHqService hq) { this.hq = hq; }

    @PreAuthorize("@perm.can('chain.admin.manage')")
    @GetMapping
    public List<ChainAdminDto> list() {
        return hq.admins();
    }

    public record AddChainAdminRequest(String email, String phone) {}

    @PreAuthorize("@perm.can('chain.admin.manage')")
    @PostMapping
    public ChainAdminDto add(@RequestBody AddChainAdminRequest req) {
        return hq.add(req.email(), req.phone());
    }

    /** {@code reason} in the body is required: somebody lost the keys to the chain, and why is the question. */
    public record DeactivateChainAdminRequest(String reason) {}

    @PreAuthorize("@perm.can('chain.admin.manage')")
    @Audited(action = "chain.admin_deactivated", targetType = "user_account", idParam = "accountId")
    @PostMapping("/{accountId}/deactivate")
    public ChainAdminDto deactivate(@PathVariable UUID accountId,
                                    @RequestBody DeactivateChainAdminRequest req) {
        return hq.deactivate(accountId);
    }
}
