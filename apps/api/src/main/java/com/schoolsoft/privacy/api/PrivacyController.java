package com.schoolsoft.privacy.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolsoft.audit.api.Audited;
import com.schoolsoft.iam.api.SelfScope;
import com.schoolsoft.platform.security.Perm;
import com.schoolsoft.privacy.internal.PrivacyService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * A family's rights over their child's data, and the office's side of
 * serving them (SEC-09).
 *
 * <p>Everything a family can reach here is gated on {@code privacy.own} and
 * then narrowed to their own child by {@link SelfScope} — the permission says
 * a parent may ask, not about whom. The two decisions on an erasure are the
 * office's alone and audited with a reason: one removes a person from the
 * record and the other tells a family no.</p>
 */
@RestController
@RequestMapping("/v1/privacy")
public class PrivacyController {

    private final PrivacyService service;
    private final SelfScope selfScope;

    public PrivacyController(PrivacyService service, SelfScope selfScope) {
        this.service = service;
        this.selfScope = selfScope;
    }

    // ---------------------------------------------------------------- consent

    @PreAuthorize("@perm.canAnyOf('privacy.manage', 'privacy.own')")
    @GetMapping("/students/{studentId}/consents")
    public List<ConsentDto> consents(@PathVariable UUID studentId) {
        selfScope.requireStudent(studentId, Perm.PRIVACY_MANAGE);
        return service.consents(studentId);
    }

    /**
     * @param source where the answer was given — {@code parent_app}, or
     *               {@code paper} when the office is recording a signed form
     */
    public record ConsentBody(@NotBlank String purpose, @NotNull Boolean granted, String source) {}

    @PreAuthorize("@perm.canAnyOf('privacy.manage', 'privacy.own')")
    @PostMapping("/students/{studentId}/consents")
    public List<ConsentDto> setConsent(@PathVariable UUID studentId, @RequestBody ConsentBody body) {
        selfScope.requireStudent(studentId, Perm.PRIVACY_MANAGE);
        return service.setConsent(studentId, body.purpose(), Boolean.TRUE.equals(body.granted()), body.source());
    }

    // --------------------------------------------------------------- requests

    public record FileBody(@NotNull UUID studentId, @NotBlank String kind, String note) {}

    @PreAuthorize("@perm.canAnyOf('privacy.manage', 'privacy.own')")
    @PostMapping("/requests")
    public DataRequestDto file(@RequestBody FileBody body) {
        selfScope.requireStudent(body.studentId(), Perm.PRIVACY_MANAGE);
        return service.file(body.studentId(), body.kind(), body.note());
    }

    /** The office's queue, or a family's own requests — the same list, narrowed. */
    @PreAuthorize("@perm.canAnyOf('privacy.manage', 'privacy.own')")
    @GetMapping("/requests")
    public List<DataRequestDto> requests(@RequestParam(required = false) String status) {
        return selfScope.narrowToOwnStudents(
            service.requests(status), DataRequestDto::studentId, Perm.PRIVACY_MANAGE);
    }

    @PreAuthorize("@perm.canAnyOf('privacy.manage', 'privacy.own')")
    @GetMapping("/requests/{id}")
    public DataRequestDto request(@PathVariable UUID id) {
        DataRequestDto request = service.request(id);
        selfScope.requireStudent(request.studentId(), Perm.PRIVACY_MANAGE);
        return request;
    }

    @PreAuthorize("@perm.canAnyOf('privacy.manage', 'privacy.own')")
    @GetMapping("/requests/{id}/export")
    public JsonNode export(@PathVariable UUID id) {
        selfScope.requireStudent(service.request(id).studentId(), Perm.PRIVACY_MANAGE);
        return service.exportFor(id);
    }

    public record DecisionBody(@NotBlank String reason) {}

    @PreAuthorize("@perm.can('privacy.manage')")
    @PostMapping("/requests/{id}/fulfil")
    @Audited(action = "privacy.request_fulfilled", targetType = "data_request")
    public DataRequestDto fulfil(@PathVariable UUID id, @RequestBody DecisionBody body) {
        return service.fulfil(id, body.reason());
    }

    @PreAuthorize("@perm.can('privacy.manage')")
    @PostMapping("/requests/{id}/refuse")
    @Audited(action = "privacy.request_refused", targetType = "data_request")
    public DataRequestDto refuse(@PathVariable UUID id, @RequestBody DecisionBody body) {
        return service.refuse(id, body.reason());
    }
}
