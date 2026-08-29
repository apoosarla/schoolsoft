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
    private final AuditChainVerifier verifier;

    public AuditController(AuditService audit, AuditChainVerifier verifier) {
        this.audit = audit;
        this.verifier = verifier;
    }

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

    /**
     * Walks the log's hash chain and says whether it still holds.
     *
     * <p>The chain (V027) makes tampering detectable; this is what does the
     * detecting, and it is the answer to the only question an auditor asks of
     * an audit log — "has anything been changed since it was written". Reads
     * the whole log, so it is a deliberate action rather than something a
     * dashboard polls.</p>
     */
    @PreAuthorize("@perm.can('audit.view')")
    @GetMapping("/chain")
    public AuditChainVerifier.Result verifyChain() {
        return verifier.verify();
    }
}
