package com.schoolsoft.audit.api;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads the operators' own trail (SEC-06). Platform-admin only, and itself a
 * request made as one — so reading the trail leaves a row in it.
 */
@RestController
@RequestMapping("/v1/platform-admin/audit")
public class OperatorAuditController {

    private final OperatorTrail trail;

    public OperatorAuditController(OperatorTrail trail) { this.trail = trail; }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping
    public List<OperatorAuditEntryDto> query(
        @RequestParam(required = false) UUID chainId,
        @RequestParam(required = false) UUID actorUserId,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return trail.query(chainId, actorUserId, Math.min(limit, 500));
    }
}
