package com.schoolsoft.tenancy.api;

import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.tenancy.internal.SchoolRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The academic year's state changes, as an operation other modules may call.
 *
 * {@link SchoolController} drives this from the calendar screen; rollover
 * drives the same three steps from the wizard — a commit closes the year it
 * came from, a roll-back reopens it, and activation is the deliberate act that
 * makes the new year the live one. Sharing the service rather than the SQL is
 * what keeps every one of those audited the same way.
 */
@Service
public class AcademicYearLifecycle {

    private final SchoolRepository repo;
    private final AuditService audit;

    public AcademicYearLifecycle(SchoolRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public AcademicYearDto find(UUID academicYearId) {
        return repo.findAcademicYear(academicYearId);
    }

    public AcademicYearDto close(UUID academicYearId, UUID actingStaffId, String reason) {
        return setStatus(academicYearId, "closed", actingStaffId, reason);
    }

    /** Reopening a closed year needs a reason; the audit row is the point of it. */
    public AcademicYearDto reopen(UUID academicYearId, UUID actingStaffId, String reason) {
        return setStatus(academicYearId, "active", actingStaffId, reason);
    }

    /**
     * Makes a planned year the live one: active, and the school's current year.
     * Deliberately distinct from committing a rollover — until this is called
     * the new year exists but nothing points at it, which is the window in
     * which the whole thing can still be undone.
     */
    public AcademicYearDto activate(UUID academicYearId, UUID actingStaffId) {
        AcademicYearDto before = repo.findAcademicYear(academicYearId);
        AcademicYearDto after = repo.activateAcademicYear(academicYearId, actingStaffId);
        audit.record("academic_year.activated", "academic_year", academicYearId,
            Map.of("status", before.status(), "isCurrent", before.isCurrent()),
            Map.of("status", after.status(), "isCurrent", after.isCurrent()));
        return after;
    }

    public AcademicYearDto setStatus(UUID academicYearId, String status, UUID actingStaffId, String reason) {
        AcademicYearDto before = repo.findAcademicYear(academicYearId);
        AcademicYearDto after = repo.setAcademicYearStatus(academicYearId, status, actingStaffId, reason);
        audit.record("academic_year.status_changed", "academic_year", academicYearId,
            Map.of("status", before.status()),
            Map.of("status", after.status(), "reason", reason == null ? "" : reason));
        return after;
    }
}
