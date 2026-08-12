package com.schoolsoft.attendance.api;

import com.schoolsoft.attendance.internal.AttendanceAmendmentService;
import com.schoolsoft.attendance.internal.AttendanceAuthorizer;
import com.schoolsoft.attendance.internal.AttendancePolicyRepository;
import com.schoolsoft.attendance.internal.AttendanceRepository;
import com.schoolsoft.audit.api.Audited;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/attendance")
public class AttendanceController {

    private final AttendanceRepository repo;
    private final AttendanceAuthorizer authorizer;
    private final AttendanceAmendmentService amendments;
    private final AttendancePolicyRepository policies;

    public AttendanceController(AttendanceRepository repo, AttendanceAuthorizer authorizer,
                                AttendanceAmendmentService amendments, AttendancePolicyRepository policies) {
        this.repo = repo;
        this.authorizer = authorizer;
        this.amendments = amendments;
        this.policies = policies;
    }

    public record MarkRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, @NotNull UUID sectionId, @NotNull LocalDate onDate,
        Integer periodNo, @NotBlank String status, String source, UUID markedByStaffId, String notes
    ) {}

    @PostMapping("/mark")
    public AttendanceRecordDto mark(@RequestBody MarkRequest req) {
        authorizer.requireMayMark(req.sectionId(), req.onDate(), req.periodNo());
        return repo.mark(
            req.schoolId(), req.studentId(), req.sectionId(), req.onDate(), req.periodNo(),
            req.status(), req.source() == null ? "manual" : req.source(), req.markedByStaffId(), req.notes()
        );
    }

    public record BulkMarkRequest(
        @NotNull UUID schoolId, @NotNull UUID sectionId, @NotNull LocalDate onDate, Integer periodNo,
        String source, UUID markedByStaffId, @NotNull List<StudentStatus> entries
    ) {}

    public record StudentStatus(@NotNull UUID studentId, @NotBlank String status, String notes) {}

    @PostMapping("/mark/bulk")
    public List<AttendanceRecordDto> markBulk(@RequestBody BulkMarkRequest req) {
        authorizer.requireMayMark(req.sectionId(), req.onDate(), req.periodNo());
        return req.entries().stream()
            .map(e -> repo.mark(
                req.schoolId(), e.studentId(), req.sectionId(), req.onDate(), req.periodNo(),
                e.status(), req.source() == null ? "manual" : req.source(), req.markedByStaffId(), e.notes()
            ))
            .toList();
    }

    @GetMapping
    public List<AttendanceRecordDto> forSection(@RequestParam UUID sectionId, @RequestParam LocalDate onDate) {
        return repo.forSectionOnDate(sectionId, onDate);
    }

    @GetMapping("/students/{studentId}")
    public List<AttendanceRecordDto> forStudent(
        @PathVariable UUID studentId, @RequestParam LocalDate from, @RequestParam LocalDate to
    ) {
        return repo.forStudent(studentId, from, to);
    }

    /** Attendance percentage over a range, against the school-calendar denominator. */
    @GetMapping("/students/{studentId}/summary")
    public AttendanceSummaryDto summary(
        @PathVariable UUID studentId, @RequestParam LocalDate from, @RequestParam LocalDate to
    ) {
        return repo.summaryForStudent(studentId, from, to);
    }

    // -------------------------- Leave --------------------------

    public record LeaveRequest(
        @NotNull UUID schoolId, @NotBlank String subjectType, @NotNull UUID subjectId,
        @NotNull LocalDate fromDate, @NotNull LocalDate toDate, String reason
    ) {}

    @PostMapping("/leave")
    public LeaveApplicationDto applyLeave(@RequestBody LeaveRequest req) {
        return repo.applyLeave(req.schoolId(), req.subjectType(), req.subjectId(), req.fromDate(), req.toDate(), req.reason());
    }

    @GetMapping("/leave")
    public List<LeaveApplicationDto> listLeave(@RequestParam UUID schoolId, @RequestParam(required = false) String status) {
        return repo.listLeave(schoolId, status);
    }

    public record DecideLeaveRequest(@NotBlank String status, @NotNull UUID approverStaffId) {}

    /**
     * Approving writes the covered working days into the register and rejecting
     * an approval takes them back out again (ATT-05, ATT-13) — which is why
     * this is audited: it changes attendance for a range of dates at once.
     */
    @PostMapping("/leave/{id}/decide")
    @Audited(action = "leave.decided", targetType = "leave_application", requireReason = false)
    public LeaveApplicationDto decideLeave(@PathVariable UUID id, @RequestBody DecideLeaveRequest req) {
        return repo.decideLeave(id, req.status(), req.approverStaffId());
    }

    // -------------------------- Amendments (ATT-06) --------------------------

    public record AmendRequest(
        @NotNull UUID studentId, @NotNull LocalDate onDate, Integer periodNo,
        @NotBlank String newStatus, @NotBlank String reason
    ) {}

    /** Asks for a change to a register that is past its marking window. */
    @PostMapping("/amendments")
    public AttendanceAmendmentDto requestAmendment(@RequestBody AmendRequest req) {
        return amendments.request(req.studentId(), req.onDate(), req.periodNo(),
            req.newStatus(), req.reason());
    }

    @GetMapping("/amendments")
    public List<AttendanceAmendmentDto> listAmendments(
        @RequestParam UUID schoolId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) UUID studentId
    ) {
        return amendments.list(schoolId, status, studentId);
    }

    /** The decision's own reason — why the correction was allowed, or refused. */
    public record DecideAmendmentRequest(@NotBlank String status, @NotBlank String reason) {}

    @PostMapping("/amendments/{id}/decide")
    @Audited(action = "attendance.amendment_decided", targetType = "attendance_amendment")
    public AttendanceAmendmentDto decideAmendment(
        @PathVariable UUID id, @RequestBody DecideAmendmentRequest req
    ) {
        return amendments.decide(id, req.status(), req.reason());
    }

    // -------------------------- Marking policy --------------------------

    @GetMapping("/policy")
    public AttendancePolicyRepository.Policy policy(@RequestParam UUID schoolId) {
        return policies.of(schoolId);
    }

    public record PolicyRequest(@NotNull UUID schoolId, Integer editWindowHours, List<String> approverRoles) {}

    @PutMapping("/policy")
    public AttendancePolicyRepository.Policy setPolicy(@RequestBody PolicyRequest req) {
        return policies.upsert(req.schoolId(), req.editWindowHours(), req.approverRoles());
    }
}
