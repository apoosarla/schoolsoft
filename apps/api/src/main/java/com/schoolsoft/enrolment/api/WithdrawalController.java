package com.schoolsoft.enrolment.api;

import com.schoolsoft.audit.api.Audited;
import com.schoolsoft.enrolment.internal.WithdrawalService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The leavers' desk (XFER-01/03/08).
 *
 * <p>Every mutation here is audited. A withdrawal is the change a family
 * disputes most often and the one that is hardest to reconstruct afterwards —
 * the enrolment it closed looks, a year later, exactly like an enrolment that
 * ended for any other reason.</p>
 */
@RestController
@RequestMapping("/v1/enrolment/withdrawals")
public class WithdrawalController {

    private final WithdrawalService service;

    public WithdrawalController(WithdrawalService service) {
        this.service = service;
    }

    @PreAuthorize("@perm.can('withdrawal.view')")
    @GetMapping
    public List<WithdrawalDto> list(@RequestParam UUID schoolId,
                                    @RequestParam(required = false) String state) {
        return service.list(schoolId, state);
    }

    @PreAuthorize("@perm.can('withdrawal.view')")
    @GetMapping("/{id}")
    public WithdrawalDto find(@PathVariable UUID id) {
        return service.find(id);
    }

    /**
     * Deliberately staff-only, including for the child's own family. What
     * blocks a clearance line is the school's internal position on a debt, and
     * a parent reading "blocked: ₹18,400 outstanding" off an API before anybody
     * has spoken to them is not how that conversation should start. The family
     * sees the certificate once it is issued.
     */
    @PreAuthorize("@perm.can('withdrawal.view')")
    @GetMapping("/students/{studentId}")
    public List<WithdrawalDto> forStudent(@PathVariable UUID studentId) {
        return service.forStudent(studentId);
    }

    /**
     * {@code lastWorkingDate} is the last day the child attends, and everything
     * downstream keys off it rather than off today: a form filled in on the 1st
     * for a child leaving on the 30th must not empty their desk on the 1st.
     */
    public record InitiateRequest(
        @NotNull UUID enrolmentId,
        @NotBlank String reasonCode,
        @NotBlank String reason,
        @NotNull LocalDate lastWorkingDate
    ) {}

    @PreAuthorize("@perm.can('withdrawal.manage')")
    @PostMapping
    @Audited(action = "withdrawal.initiate", targetType = "withdrawal", idParam = "enrolmentId",
             snapshot = false)
    public WithdrawalDto initiate(@RequestBody InitiateRequest req) {
        return service.initiate(req.enrolmentId(), req.reasonCode(), req.reason(), req.lastWorkingDate());
    }

    /** Re-asks fees, the library and transport. Pressed after a parent pays. */
    @PreAuthorize("@perm.can('withdrawal.manage')")
    @PostMapping("/{id}/clearance/refresh")
    public WithdrawalDto refresh(@PathVariable UUID id) {
        return service.refreshClearance(id);
    }

    public record ResolveRequest(String reason) {}

    /** "This is genuinely settled now" — the ordinary path, for the desk. */
    @PreAuthorize("@perm.can('withdrawal.manage')")
    @PostMapping("/{id}/clearance/{area}")
    @Audited(action = "withdrawal.clearance_cleared", targetType = "withdrawal")
    public WithdrawalDto clear(@PathVariable UUID id, @PathVariable String area,
                               @RequestBody ResolveRequest req) {
        return service.clearItem(id, area, req.reason());
    }

    /**
     * "Let them go anyway" — the authorised override of XFER-08, and a different
     * endpoint because it is a different decision by a different person. Waiving
     * the fees line is what {@code dues_override_*} records on the withdrawal.
     *
     * <p>Separate rather than a {@code state} field on the call above, so the
     * permission is declared where every other one is: on the annotation, where
     * a reviewer asking "who may forgive arrears" can find it.</p>
     */
    @PreAuthorize("@perm.can('withdrawal.override')")
    @PostMapping("/{id}/clearance/{area}/waive")
    @Audited(action = "withdrawal.clearance_waived", targetType = "withdrawal")
    public WithdrawalDto waive(@PathVariable UUID id, @PathVariable String area,
                               @RequestBody ResolveRequest req) {
        return service.waiveItem(id, area, req.reason());
    }

    public record CompleteRequest(String reason) {}

    /**
     * The point of no return: the enrolment closes, the bus seat is released,
     * and the child comes off every register from the last working day.
     * Re-running it returns the completed withdrawal rather than a conflict.
     */
    @PreAuthorize("@perm.can('withdrawal.manage')")
    @PostMapping("/{id}/complete")
    @Audited(action = "withdrawal.complete", targetType = "withdrawal")
    public WithdrawalDto complete(@PathVariable UUID id, @RequestBody(required = false) CompleteRequest req) {
        return service.complete(id);
    }

    public record CancelRequest(@NotBlank String reason) {}

    @PreAuthorize("@perm.can('withdrawal.manage')")
    @PostMapping("/{id}/cancel")
    @Audited(action = "withdrawal.cancel", targetType = "withdrawal")
    public WithdrawalDto cancel(@PathVariable UUID id, @RequestBody CancelRequest req) {
        return service.cancel(id, req.reason());
    }
}
