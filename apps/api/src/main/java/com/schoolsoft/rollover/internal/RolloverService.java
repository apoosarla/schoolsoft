package com.schoolsoft.rollover.internal;

import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.enrolment.api.RollNumbers;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.rollover.api.RolloverAllocationDto;
import com.schoolsoft.rollover.api.RolloverRunDto;
import com.schoolsoft.tenancy.api.AcademicYearDto;
import com.schoolsoft.tenancy.api.AcademicYearLifecycle;
import com.schoolsoft.tenancy.api.SectionCapacity;
import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rollover state machine (GAP-02).
 *
 * <pre>
 *   draft ── clone structure ──▶ structure_cloned ── allocate ──▶ allocated
 *                                                                    │
 *                                            commit (in batches) ────┤
 *                                                                    ▼
 *                                                               committed
 *                                                                    │
 *                                              roll back (until the  │
 *                                              new year is activated)│
 *                                                                    ▼
 *                                                              rolled_back
 * </pre>
 *
 * Three properties are load-bearing, and each is a thing that goes wrong in
 * real schools:
 *
 * <ul>
 *   <li><b>Idempotent.</b> The run key makes a second "start rollover" the same
 *       run; an allocation already {@code applied} is skipped, so a commit that
 *       died half way resumes rather than enrolling half the school twice.</li>
 *   <li><b>Restartable.</b> Commit works in batches and returns what is left,
 *       so an interruption costs one batch, not the run.</li>
 *   <li><b>Reversible.</b> Until the new year is activated, roll-back deletes
 *       exactly what the run created and puts the old enrolments back.</li>
 * </ul>
 *
 * Closing the source year is the last act of a complete commit, and only of a
 * complete one: while any child is unplaced or undecided, the year stays open,
 * because a closed year with an active enrolment and no next seat is a child
 * nobody can find.
 */
@Service
public class RolloverService {

    private final JdbcTemplate jdbc;
    private final RolloverRepository repo;
    private final RolloverReadiness readiness;
    private final StructureCloner cloner;
    private final AllocationPlanner planner;
    private final CarryForward carryForward;
    private final AcademicYearLifecycle academicYears;
    private final SectionCapacity capacity;
    private final RollNumbers rollNumbers;
    private final AuditService audit;

    public RolloverService(JdbcTemplate jdbc, RolloverRepository repo, RolloverReadiness readiness,
                           StructureCloner cloner, AllocationPlanner planner, CarryForward carryForward,
                           AcademicYearLifecycle academicYears, SectionCapacity capacity,
                           RollNumbers rollNumbers, AuditService audit) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.readiness = readiness;
        this.cloner = cloner;
        this.planner = planner;
        this.carryForward = carryForward;
        this.academicYears = academicYears;
        this.capacity = capacity;
        this.rollNumbers = rollNumbers;
        this.audit = audit;
    }

    public record CommitResult(UUID runId, String state, int applied, int graduated, int remaining,
                               int unplaced, int withoutDecision, boolean sourceYearClosed,
                               double arrearsCarried) {}

    // ------------------------------------------------------------------ start

    /**
     * Starts (or re-finds) a run. Passing the same {@code runKey} twice returns
     * the first run rather than starting a second one — the wizard's back
     * button and a double-clicked button are the same event to a school.
     */
    @Transactional
    public RolloverRunDto start(UUID schoolId, UUID fromAyId, UUID toAyId, String runKey,
                                Integer batchSize, UUID startedByStaffId) {
        String key = runKey == null || runKey.isBlank() ? "rollover:" + fromAyId : runKey;
        var existing = repo.findByRunKey(schoolId, key);
        if (existing.isPresent()) return existing.get();

        AcademicYearDto from = academicYears.find(fromAyId);
        AcademicYearDto to = academicYears.find(toAyId);
        if ("closed".equals(to.status())) {
            throw new IllegalArgumentException(
                "Target year " + to.code() + " is closed; reopen it before rolling into it");
        }
        if (!to.startsOn().isAfter(from.startsOn())) {
            throw new IllegalArgumentException(
                "Target year " + to.code() + " does not follow " + from.code());
        }
        UUID id = repo.insertRun(schoolId, fromAyId, toAyId, key,
            batchSize == null || batchSize <= 0 ? 200 : batchSize, startedByStaffId);
        audit.record("rollover.started", "rollover_run", id, null,
            Map.of("from", from.code(), "to", to.code(), "runKey", key));
        return repo.findRun(id);
    }

    public com.schoolsoft.rollover.api.ReadinessReportDto checkReadiness(UUID schoolId, UUID academicYearId) {
        return readiness.check(schoolId, academicYearId);
    }

    // -------------------------------------------------------- clone + allocate

    @Transactional
    public RolloverRunDto cloneStructure(UUID runId) {
        RolloverRunDto run = repo.findRun(runId);
        requireOpen(run);
        var result = cloner.clone(run.schoolId(),
            academicYears.find(run.fromAcademicYearId()), academicYears.find(run.toAcademicYearId()));
        repo.mergeStats(runId, Map.of(
            "sectionsCloned", result.sectionsCloned(),
            "sectionsAlreadyThere", result.sectionsAlreadyThere(),
            "feeStructuresCloned", result.feeStructuresCloned()));
        if ("draft".equals(run.state())) repo.setState(runId, "structure_cloned");
        return repo.findRun(runId);
    }

    @Transactional
    public RolloverRunDto allocate(UUID runId) {
        RolloverRunDto run = repo.findRun(runId);
        requireOpen(run);
        var result = planner.plan(run);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("planned", result.planned());
        stats.put("promoting", result.promoting());
        stats.put("detaining", result.detaining());
        stats.put("graduating", result.graduating());
        stats.put("unplaced", result.unplaced());
        stats.put("withoutDecision", result.withoutDecision());
        repo.mergeStats(runId, stats);
        repo.setState(runId, "allocated");
        return repo.findRun(runId);
    }

    /** A human moving one child: the only way a seat differs from the plan. */
    @Transactional
    public RolloverAllocationDto reallocate(UUID allocationId, UUID toSectionId, String rollNo,
                                            String overCapacityReason, String note) {
        RolloverAllocationDto allocation = repo.findAllocation(allocationId);
        RolloverRunDto run = repo.findRun(allocation.rolloverRunId());
        requireOpen(run);
        if ("applied".equals(allocation.state())) {
            throw new IllegalArgumentException(
                "This child has already been moved; roll the run back to change where they went");
        }
        if (toSectionId != null) capacity.reserveSeat(toSectionId, overCapacityReason);
        repo.updateAllocationTarget(allocationId, toSectionId, rollNo, overCapacityReason,
            note, toSectionId == null ? "skipped" : "planned");
        return repo.findAllocation(allocationId);
    }

    // ----------------------------------------------------------------- commit

    /**
     * Applies up to {@code maxBatches} batches of the plan. Called again, it
     * continues; called after the last batch, it closes the source year and
     * finishes the run.
     */
    @Transactional
    public CommitResult commit(UUID runId, Integer maxBatches, UUID actingStaffId) {
        RolloverRunDto run = repo.findRun(runId);
        if ("rolled_back".equals(run.state())) {
            throw new IllegalArgumentException("This run was rolled back; start a new one");
        }
        if ("draft".equals(run.state())) {
            throw new IllegalArgumentException("Nothing to commit: allocate the children first");
        }
        AcademicYearDto from = academicYears.find(run.fromAcademicYearId());
        AcademicYearDto to = academicYears.find(run.toAcademicYearId());
        if ("closed".equals(to.status())) {
            throw new ForbiddenException("Target year " + to.code() + " is closed");
        }

        int batches = maxBatches == null || maxBatches <= 0 ? Integer.MAX_VALUE : maxBatches;
        int applied = 0;
        int graduated = 0;
        double arrears = 0;
        int batchesRun = 0;

        while (batchesRun < batches) {
            List<RolloverAllocationDto> batch = repo.nextPlannedBatch(runId, run.batchSize());
            if (batch.isEmpty()) break;
            for (RolloverAllocationDto allocation : batch) {
                arrears += apply(run, allocation, from, to);
                if ("graduate".equals(allocation.decision())) graduated++;
                applied++;
            }
            batchesRun++;
            repo.bumpBatches(runId, 1);
            if (batch.size() < run.batchSize()) break;
        }

        int unplaced = countUnplaced(runId);
        int withoutDecision = repo.countAllocations(runId, "skipped");
        int remaining = repo.countAllocations(runId, "planned");

        boolean closed = false;
        if (remaining == 0 && unplaced == 0 && withoutDecision == 0) {
            academicYears.close(from.id(), actingStaffId, "Rolled over into " + to.code());
            repo.markCommitted(runId);
            closed = true;
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("applied", repo.countAllocations(runId, "applied"));
        stats.put("arrearsCarried", Math.round(arrears * 100.0) / 100.0);
        repo.mergeStats(runId, stats);
        audit.record("rollover.committed", "rollover_run", runId, null,
            Map.of("applied", applied, "graduated", graduated, "remaining", remaining,
                   "sourceYearClosed", closed));

        return new CommitResult(runId, repo.findRun(runId).state(), applied, graduated, remaining,
            unplaced, withoutDecision, closed, Math.round(arrears * 100.0) / 100.0);
    }

    /** One child. Everything here is undone by {@link #rollback} and nothing else. */
    private double apply(RolloverRunDto run, RolloverAllocationDto allocation,
                         AcademicYearDto from, AcademicYearDto to) {
        String closingStatus = switch (allocation.decision()) {
            case "graduate" -> "graduated";
            case "detain" -> "detained";
            default -> "promoted";
        };
        jdbc.update("UPDATE enrolment SET status = ?, ends_on = ? WHERE id = ?",
            closingStatus, Date.valueOf(from.endsOn()), allocation.fromEnrolmentId());

        boolean graduating = "graduate".equals(allocation.decision());
        UUID newEnrolmentId = null;
        double arrears = 0;

        if (!graduating) {
            String overCapacityReason = capacity.reserveSeat(allocation.toSectionId(),
                allocation.overCapacityReason());
            String rollNo = rollNumbers.nextFor(run.schoolId(), allocation.toSectionId(), allocation.rollNo());
            newEnrolmentId = UUID.randomUUID();
            jdbc.update(
                "INSERT INTO enrolment (id, school_id, student_id, section_id, academic_year_id, starts_on, " +
                "  status, roll_no, over_capacity_reason) VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?)",
                newEnrolmentId, run.schoolId(), allocation.studentId(), allocation.toSectionId(),
                to.id(), Date.valueOf(to.startsOn()), rollNo, overCapacityReason);
            repo.recordArtifact(run.id(), allocation.id(), run.schoolId(), "enrolment", newEnrolmentId, null);

            carryForward.carryElectives(run.id(), allocation.id(), run.schoolId(),
                allocation.fromEnrolmentId(), newEnrolmentId, to);
            arrears = carryForward.carryArrears(run.id(), allocation.id(), run.schoolId(),
                allocation.studentId(), from, to);
        }

        // A graduate's bus seat ends with the year and is not replaced (GRAD-06).
        carryForward.carryTransport(run.id(), allocation.id(), run.schoolId(), allocation.studentId(),
            from, to, !graduating);

        repo.markAllocationApplied(allocation.id(), newEnrolmentId);
        return arrears;
    }

    // --------------------------------------------------------------- rollback

    /**
     * Undoes the run. Reversible only while the new year is still planned:
     * once it is activated, timetables, invoices and attendance are being
     * written against it, and unwinding would take a term's real work with it.
     */
    @Transactional
    public RolloverRunDto rollback(UUID runId, String reason, UUID actingStaffId) {
        RolloverRunDto run = repo.findRun(runId);
        AcademicYearDto to = academicYears.find(run.toAcademicYearId());
        if (to.isCurrent() || "active".equals(to.status())) {
            throw new ForbiddenException(
                "Academic year " + to.code() + " is already active; a rollover cannot be undone after "
                + "the year it created has started");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rolling back a rollover requires a reason");
        }

        for (var artifact : repo.artifactsOf(runId)) {
            switch (artifact.kind()) {
                case "enrolment" -> jdbc.update("DELETE FROM enrolment WHERE id = ?", artifact.rowId());
                case "student_subject" ->
                    jdbc.update("DELETE FROM student_subject WHERE id = ?", artifact.rowId());
                case "student_transport" ->
                    jdbc.update("DELETE FROM student_transport WHERE id = ?", artifact.rowId());
                case "student_transport_closed" ->
                    jdbc.update("UPDATE student_transport SET ends_on = NULL WHERE id = ?", artifact.rowId());
                case "fee_invoice" -> {
                    // Somebody may have paid the opening balance in the meantime.
                    // Deleting it then would take their receipt with it.
                    Integer payments = jdbc.queryForObject(
                        "SELECT count(*) FROM payment WHERE fee_invoice_id = ?", Integer.class,
                        artifact.rowId());
                    if (payments != null && payments > 0) {
                        throw new ForbiddenException(
                            "Opening-balance invoice has payments against it; refund or reverse them "
                            + "before rolling back");
                    }
                    jdbc.update("DELETE FROM fee_invoice_line WHERE fee_invoice_id = ?", artifact.rowId());
                    jdbc.update("DELETE FROM fee_invoice WHERE id = ?", artifact.rowId());
                }
                case "fee_invoice_carried" -> jdbc.update(
                    "UPDATE fee_invoice SET status = ?, updated_at = now() WHERE id = ?",
                    artifact.priorState() == null ? "open" : artifact.priorState(), artifact.rowId());
                default -> { /* unknown kind: leave it rather than delete blindly */ }
            }
        }

        // Put the children back where they were.
        var applied = repo.listAllocations(runId, "applied");
        for (RolloverAllocationDto allocation : applied) {
            jdbc.update("UPDATE enrolment SET status = 'active', ends_on = NULL WHERE id = ?",
                allocation.fromEnrolmentId());
            repo.resetAllocation(allocation.id());
        }

        AcademicYearDto from = academicYears.find(run.fromAcademicYearId());
        if ("closed".equals(from.status())) {
            academicYears.reopen(from.id(), actingStaffId, "Rollover rolled back: " + reason);
        }

        repo.deleteArtifacts(runId);
        repo.markRolledBack(runId);
        audit.record("rollover.rolled_back", "rollover_run", runId, null,
            Map.of("restored", applied.size()), reason, null);
        return repo.findRun(runId);
    }

    /**
     * Makes the new year live. Separate from commit on purpose: it is the point
     * of no return, and a school should have to mean it (YEC-07).
     */
    @Transactional
    public RolloverRunDto activate(UUID runId, UUID actingStaffId) {
        RolloverRunDto run = repo.findRun(runId);
        if (!"committed".equals(run.state())) {
            throw new IllegalArgumentException(
                "Only a committed rollover can be activated; this one is " + run.state());
        }
        academicYears.activate(run.toAcademicYearId(), actingStaffId);
        return repo.findRun(runId);
    }

    // ------------------------------------------------------------------ reads

    public RolloverRunDto find(UUID runId) { return repo.findRun(runId); }

    public List<RolloverRunDto> list(UUID schoolId) { return repo.listRuns(schoolId); }

    public List<RolloverAllocationDto> allocations(UUID runId, String state) {
        return repo.listAllocations(runId, state);
    }

    private int countUnplaced(UUID runId) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM rollover_allocation WHERE rollover_run_id = ? AND state = 'planned' " +
            "  AND to_section_id IS NULL AND decision <> 'graduate'", Integer.class, runId);
        return n == null ? 0 : n;
    }

    private void requireOpen(RolloverRunDto run) {
        if ("committed".equals(run.state()) || "rolled_back".equals(run.state())) {
            throw new IllegalArgumentException("Rollover run is " + run.state() + " and cannot be changed");
        }
    }
}
