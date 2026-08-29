package com.schoolsoft.audit.api;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/audit")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) { this.audit = audit; }

    @PreAuthorize("@perm.can('audit.view')")
    @GetMapping
    public List<AuditLogEntryDto> query(
        @RequestParam(required = false) String targetType,
        @RequestParam(required = false) UUID targetId,
        @RequestParam(required = false) UUID actorUserId,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return audit.query(targetType, targetId, actorUserId, Math.min(limit, 500));
    }
}
