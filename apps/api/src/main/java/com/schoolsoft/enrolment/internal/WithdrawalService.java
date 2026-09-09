package com.schoolsoft.enrolment.internal;

import com.schoolsoft.enrolment.api.ClearanceProbe;
import com.schoolsoft.enrolment.api.EnrolmentDto;
import com.schoolsoft.enrolment.api.WithdrawalDto;
import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.platform.web.ConflictException;
import com.schoolsoft.platform.web.NotFoundException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taking a child off the school's books (GAP-03, XFER-01/03/08).
 *
 * <p>The shape is a checklist that gates a state change. Filing the withdrawal
 * asks every area of the school what is still outstanding; completing it is
 * refused while any of those answers is still blocking, unless somebody holding
 * {@code withdrawal.override} says otherwise <em>and says why</em>. Only then
 * does the enrolment close, the bus seat go back, and the child stop appearing
 * on registers — from the last working day, not from the day the form was
 * filled in.</p>
 *
 * <h2>Areas</h2>
 * The checklist is assembled from the {@link ClearanceProbe}s on the classpath
 * plus {@link #MANUAL_AREAS}, so fees, library and transport answer for
 * themselves and "assets" — the ID card, the locker key, the lab coat, none of
 * which this system tracks — waits for a human to sign it off. That is the
 * honest representation: an area with no record behind it should say "somebody
 * has to check" rather than quietly report clear.
 */
@Service
public class WithdrawalService {

    /**
     * Areas with no system of record behind them. They open {@code pending} and
     * only a person moves them, which is what stops a checklist from certifying
     * something nobody looked at.
     */
    private static final List<String> MANUAL_AREAS = List.of("assets");

    /** What a withdrawal reason means for the enrolment it closes. */
    private static final Map<String, String> ENROLMENT_STATUS = Map.of(
        "graduation", "graduated",
        "transfer_out", "transferred");

    private final WithdrawalRepository repo;
    private final EnrolmentRepository enrolments;
    private final Authz authz;
    private final Map<String, ClearanceProbe> probes = new LinkedHashMap<>();

    public WithdrawalService(WithdrawalRepository repo, EnrolmentRepository enrolments, Authz authz,
                             List<ClearanceProbe> probes) {
        this.repo = repo;
        this.enrolments = enrolments;
        this.authz = authz;
        for (ClearanceProbe probe : probes) this.probes.put(probe.area(), probe);
    }

    // ------------------------------------------------------------------ reads

    public WithdrawalDto find(UUID id) { return repo.require(id); }

    public List<WithdrawalDto> list(UUID schoolId, String state) { return repo.list(schoolId, state); }

    public List<WithdrawalDto> forStudent(UUID studentId) { return repo.listForStudent(studentId); }

    // ---------------------------------------------------------------- writes

    /**
     * Files the withdrawal and runs the checklist in the same breath. Running
     * the probes up front rather than at completion is what makes the arrears
     * visible to the registrar on the day they take the phone call, instead of
     * three weeks later when the TC is refused.
     */
    @Transactional
    public WithdrawalDto initiate(UUID enrolmentId, String reasonCode, String reason,
                                  LocalDate lastWorkingDate) {
        EnrolmentDto enrolment = enrolments.find(enrolmentId)
            .orElseThrow(() -> new NotFoundException("Enrolment not found: " + enrolmentId));

        if (!"active".equals(enrolment.status())) {
            throw new ConflictException("That enrolment is already " + enrolment.status()
                + "; there is nothing to withdraw from");
        }
        if (lastWorkingDate.isBefore(enrolment.startsOn())) {
            throw new ConflictException("The last working date is before the child joined ("
                + enrolment.startsOn() + ")");
        }
        // A second open withdrawal is a mistake at the counter. The partial
        // unique index refuses it too; naming it here is the difference between
        // a 409 that explains itself and one that quotes a constraint.
        if (!repo.openForStudent(enrolment.studentId()).isEmpty()) {
            throw new ConflictException("This student already has a withdrawal in progress");
        }

        UUID id = repo.insert(enrolment.schoolId(), enrolment.studentId(), enrolmentId,
            reasonCode, reason, lastWorkingDate, authz.currentStaffId());
        runProbes(enrolment.schoolId(), id, enrolment.studentId(), lastWorkingDate);
        return settleState(id);
    }

    /** Re-asks every area. The registrar presses this after a parent pays. */
    @Transactional
    public WithdrawalDto refreshClearance(UUID id) {
        WithdrawalDto w = repo.require(id);
        requireOpen(w);
        runProbes(w.schoolId(), w.id(), w.studentId(), w.lastWorkingDate());
        return settleState(id);
    }

    /**
     * Marks one line settled — the ordinary "this is genuinely paid, returned or
     * handed back now". Safe for the desk that processes the exit to do, because
     * a probe that still disagrees says so on the next refresh.
     */
    @Transactional
    public WithdrawalDto clearItem(UUID id, String area, String reason) {
        return resolve(id, area, "cleared", reason);
    }

    /**
     * Lets a blocking line through without it having been settled — the
     * authorised override of XFER-08.
     *
     * <p>A separate method from {@link #clearItem} because it is a separate
     * permission, and the permission is declared on its own endpoint rather than
     * checked in here: a gate that lives in a service is a gate nobody reviewing
     * "who may do this" will find, which is the whole reason {@code Perm} is an
     * enumerable vocabulary.</p>
     */
    @Transactional
    public WithdrawalDto waiveItem(UUID id, String area, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ConflictException("A waiver needs a reason");
        }
        WithdrawalDto result = resolve(id, area, "waived", reason);
        // The money override is recorded on the withdrawal itself, not only on
        // the checklist line: "who let this family leave owing us money, and why"
        // is a question about the exit.
        if ("fees".equals(area)) {
            repo.recordDuesOverride(id, reason, authz.currentStaffId());
            return repo.require(id);
        }
        return result;
    }

    private WithdrawalDto resolve(UUID id, String area, String state, String reason) {
        requireOpen(repo.require(id));
        if (!repo.resolveItem(id, area, state, reason, authz.currentStaffId())) {
            throw new NotFoundException("No '" + area + "' line on this withdrawal");
        }
        return settleState(id);
    }

    /**
     * Completes the exit: the enrolment closes on the last working day, each
     * area winds itself down, and the student's own status follows.
     *
     * <p>Re-running it is not an error. A completed withdrawal is the state the
     * caller asked for, and a retry after a dropped response must not answer 409
     * because the first attempt worked.</p>
     */
    @Transactional
    public WithdrawalDto complete(UUID id) {
        WithdrawalDto w = repo.require(id);
        if ("completed".equals(w.state())) return w;
        if ("cancelled".equals(w.state())) {
            throw new ConflictException("That withdrawal was cancelled");
        }

        List<String> blocking = w.items().stream()
            .filter(com.schoolsoft.enrolment.api.ClearanceItemDto::blocking)
            .map(item -> item.area() + " (" + item.detail() + ")")
            .toList();
        if (!blocking.isEmpty()) {
            throw new ConflictException("Clearance is not complete: " + String.join(", ", blocking)
                + ". Settle each line, or waive it with a reason.");
        }

        if (repo.complete(id, authz.currentStaffId()) == 0) {
            // Somebody moved it between the read and the write.
            return repo.require(id);
        }

        String status = ENROLMENT_STATUS.getOrDefault(w.reasonCode(), "withdrawn");
        repo.closeEnrolment(w.enrolmentId(), status, w.lastWorkingDate());
        repo.setStudentStatus(w.studentId(), "graduated".equals(status) ? "graduated" : status);
        for (ClearanceProbe probe : probes.values()) {
            probe.settle(w.studentId(), w.lastWorkingDate());
        }
        return repo.require(id);
    }

    /** The family changed their mind. The enrolment was never touched, so nothing unwinds. */
    @Transactional
    public WithdrawalDto cancel(UUID id, String reason) {
        WithdrawalDto w = repo.require(id);
        if ("cancelled".equals(w.state())) return w;
        if (repo.cancel(id, reason) == 0) {
            throw new ConflictException("That withdrawal is " + repo.require(id).state()
                + " and cannot be cancelled");
        }
        return repo.require(id);
    }

    // ----------------------------------------------------------------- inside

    private void runProbes(UUID schoolId, UUID withdrawalId, UUID studentId, LocalDate lastWorkingDate) {
        for (ClearanceProbe probe : probes.values()) {
            ClearanceProbe.Finding finding = probe.probe(studentId, lastWorkingDate);
            repo.upsertProbedItem(schoolId, withdrawalId, probe.area(),
                finding.blocking() ? "blocked" : "cleared", finding.detail(), finding.amount());
        }
        for (String area : MANUAL_AREAS) {
            repo.ensureManualItem(schoolId, withdrawalId, area,
                "Nothing tracks this — the office signs it off");
        }
    }

    /** Moves between {@code clearance_pending} and {@code cleared} to match the checklist. */
    private WithdrawalDto settleState(UUID id) {
        WithdrawalDto w = repo.require(id);
        if (List.of("completed", "cancelled").contains(w.state())) return w;
        repo.transition(id, List.of("draft", "clearance_pending", "cleared"),
            w.clear() ? "cleared" : "clearance_pending");
        return repo.require(id);
    }

    private void requireOpen(WithdrawalDto w) {
        if (List.of("completed", "cancelled").contains(w.state())) {
            throw new ConflictException("That withdrawal is " + w.state());
        }
    }
}
