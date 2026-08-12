package com.schoolsoft.attendance.api;

import com.schoolsoft.attendance.internal.AttendanceRepository;
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
    public AttendanceController(AttendanceRepository repo) { this.repo = repo; }

    public record MarkRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, @NotNull UUID sectionId, @NotNull LocalDate onDate,
        Integer periodNo, @NotBlank String status, String source, UUID markedByStaffId, String notes
    ) {}

    @PostMapping("/mark")
    public AttendanceRecordDto mark(@RequestBody MarkRequest req) {
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

    @PostMapping("/leave/{id}/decide")
    public LeaveApplicationDto decideLeave(@PathVariable UUID id, @RequestBody DecideLeaveRequest req) {
        return repo.decideLeave(id, req.status(), req.approverStaffId());
    }
}
