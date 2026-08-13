package com.schoolsoft.rollover.api;

import com.schoolsoft.audit.api.Audited;
import com.schoolsoft.rollover.internal.RolloverService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Year closure and rollover (GAP-02).
 *
 * The endpoints are the wizard's steps in order — check, clone, allocate,
 * commit — plus the two that matter when it goes wrong: roll back, and the
 * deliberate activation that ends the window in which rolling back is possible.
 */
@RestController
@RequestMapping("/v1/rollover")
public class RolloverController {

    private final RolloverService service;

    public RolloverController(RolloverService service) { this.service = service; }

    /** What still stands between the school and closing this year (YEC-01). */
    @GetMapping("/readiness")
    public ReadinessReportDto readiness(@RequestParam UUID schoolId, @RequestParam UUID academicYearId) {
        return service.checkReadiness(schoolId, academicYearId);
    }

    public record StartRequest(
        @NotNull UUID schoolId,
        @NotNull UUID fromAcademicYearId,
        @NotNull UUID toAcademicYearId,
        /** Idempotency key: the same one twice is the same run. */
        String runKey,
        Integer batchSize,
        UUID startedByStaffId
    ) {}

    @PostMapping("/runs")
    public RolloverRunDto start(@RequestBody StartRequest req) {
        return service.start(req.schoolId(), req.fromAcademicYearId(), req.toAcademicYearId(),
            req.runKey(), req.batchSize(), req.startedByStaffId());
    }

    @GetMapping("/runs")
    public List<RolloverRunDto> runs(@RequestParam UUID schoolId) {
        return service.list(schoolId);
    }

    @GetMapping("/runs/{id}")
    public RolloverRunDto run(@PathVariable UUID id) {
        return service.find(id);
    }

    /** Next year's sections and fee structures, as a planning-state copy (YEC-02). */
    @PostMapping("/runs/{id}/clone-structure")
    public RolloverRunDto cloneStructure(@PathVariable UUID id) {
        return service.cloneStructure(id);
    }

    /** Turns each promotion decision into a seat. Inert until commit. */
    @PostMapping("/runs/{id}/allocate")
    public RolloverRunDto allocate(@PathVariable UUID id) {
        return service.allocate(id);
    }

    @GetMapping("/runs/{id}/allocations")
    public List<RolloverAllocationDto> allocations(
        @PathVariable UUID id, @RequestParam(required = false) String state
    ) {
        return service.allocations(id, state);
    }

    public record ReallocateRequest(UUID toSectionId, String rollNo, String overCapacityReason, String note) {}

    @PutMapping("/allocations/{allocationId}")
    public RolloverAllocationDto reallocate(
        @PathVariable UUID allocationId, @RequestBody ReallocateRequest req
    ) {
        return service.reallocate(allocationId, req.toSectionId(), req.rollNo(),
            req.overCapacityReason(), req.note());
    }

    public record CommitRequest(Integer maxBatches, UUID actingStaffId) {}

    /**
     * Applies the plan in batches. Call it again to continue; the source year
     * closes only when nothing is left unresolved.
     */
    @PostMapping("/runs/{id}/commit")
    @Audited(action = "rollover.commit", targetType = "rollover_run", snapshot = false,
             requireReason = false)
    public RolloverService.CommitResult commit(
        @PathVariable UUID id, @RequestBody(required = false) CommitRequest req
    ) {
        return service.commit(id, req == null ? null : req.maxBatches(),
            req == null ? null : req.actingStaffId());
    }

    public record RollbackRequest(@NotBlank String reason, UUID actingStaffId) {}

    /** Undoes everything the run created — possible only until the new year is activated. */
    @PostMapping("/runs/{id}/rollback")
    @Audited(action = "rollover.rollback", targetType = "rollover_run", snapshot = false)
    public RolloverRunDto rollback(@PathVariable UUID id, @RequestBody RollbackRequest req) {
        return service.rollback(id, req.reason(), req.actingStaffId());
    }

    public record ActivateRequest(UUID actingStaffId) {}

    /** Makes the new year the live one. After this the rollover cannot be undone. */
    @PostMapping("/runs/{id}/activate")
    @Audited(action = "rollover.activate", targetType = "rollover_run", snapshot = false,
             requireReason = false)
    public RolloverRunDto activate(
        @PathVariable UUID id, @RequestBody(required = false) ActivateRequest req
    ) {
        return service.activate(id, req == null ? null : req.actingStaffId());
    }
}
