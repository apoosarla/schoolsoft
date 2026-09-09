package com.schoolsoft.certificate.api;

import com.schoolsoft.audit.api.Audited;
import com.schoolsoft.certificate.internal.CertificateService;
import com.schoolsoft.iam.api.SelfScope;
import com.schoolsoft.platform.security.Perm;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Transfer certificates, leaving certificates and transcripts (XFER-02,
 * XFER-07, GRAD-02).
 *
 * <p>Issue and revoke are audited: a TC is a statutory document with the
 * school's name on it, and "who signed this and when" has to be answerable from
 * the record rather than from memory.</p>
 */
@RestController
@RequestMapping("/v1/certificates")
public class CertificateController {

    private final CertificateService service;
    private final SelfScope selfScope;

    public CertificateController(CertificateService service, SelfScope selfScope) {
        this.service = service;
        this.selfScope = selfScope;
    }

    @PreAuthorize("@perm.can('certificate.view')")
    @GetMapping
    public List<CertificateDto> list(@RequestParam UUID schoolId,
                                     @RequestParam(required = false) String kind) {
        return service.list(schoolId, kind);
    }

    @PreAuthorize("@perm.can('certificate.view')")
    @GetMapping("/{id}")
    public CertificateDto find(@PathVariable UUID id) {
        return service.find(id);
    }

    /** A family downloads their own child's TC — which is the point of issuing it. */
    @PreAuthorize("@perm.canAnyOf('certificate.view', 'certificate.view.own')")
    @GetMapping("/students/{studentId}")
    public List<CertificateDto> forStudent(@PathVariable UUID studentId) {
        selfScope.requireStudent(studentId, Perm.CERTIFICATE_VIEW);
        return service.forStudent(studentId);
    }

    /**
     * Whether the stored document still hashes to what was signed, and whether
     * it has since been revoked. The check a receiving school makes.
     */
    @PreAuthorize("@perm.canAnyOf('certificate.view', 'certificate.view.own')")
    @GetMapping("/{id}/verify")
    public Map<String, Object> verify(@PathVariable UUID id) {
        selfScope.requireStudent(service.find(id).studentId(), Perm.CERTIFICATE_VIEW);
        return service.verify(id);
    }

    /**
     * {@code conduct} and {@code remarks} are the registrar's to write — no
     * table holds them, and a system that invented them would be putting words
     * about a child's behaviour into a statutory document nobody typed.
     * {@code extras} carries the board-specific fields this system does not
     * model (nationality, category, NCC), if the school fills them in.
     */
    public record IssueBody(
        @NotNull UUID studentId,
        @NotBlank String kind,
        String conduct,
        String remarks,
        Map<String, Object> extras
    ) {}

    @PreAuthorize("@perm.can('certificate.issue')")
    @PostMapping
    @Audited(action = "certificate.issue", targetType = "certificate", idParam = "studentId",
             snapshot = false, requireReason = false)
    public CertificateDto issue(@RequestBody IssueBody body) {
        return service.issue(new CertificateService.IssueRequest(
            body.studentId(), body.kind(), body.conduct(), body.remarks(), body.extras()));
    }

    /**
     * The correction path. A wrong certificate is revoked and replaced, never
     * edited — {@code reissue} does both in one call so the replacement is
     * linked back to what it supersedes.
     */
    public record RevokeBody(@NotBlank String reason, IssueBody reissue) {}

    @PreAuthorize("@perm.can('certificate.revoke')")
    @PostMapping("/{id}/revoke")
    @Audited(action = "certificate.revoke", targetType = "certificate")
    public CertificateDto revoke(@PathVariable UUID id, @RequestBody RevokeBody body) {
        var reissue = body.reissue() == null ? null : new CertificateService.IssueRequest(
            body.reissue().studentId(), body.reissue().kind(), body.reissue().conduct(),
            body.reissue().remarks(), body.reissue().extras());
        return service.revoke(id, body.reason(), reissue);
    }
}
