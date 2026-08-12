package com.schoolsoft.enrolment.api;

import com.schoolsoft.audit.api.Audited;
import com.schoolsoft.enrolment.internal.EnrolmentRepository;
import com.schoolsoft.enrolment.internal.StudentSubjectRepository;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/enrolment")
public class EnrolmentController {

    private final EnrolmentRepository repo;
    private final StudentSubjectRepository subjects;
    private final SubjectSetResolver subjectSets;

    public EnrolmentController(EnrolmentRepository repo, StudentSubjectRepository subjects,
                               SubjectSetResolver subjectSets) {
        this.repo = repo;
        this.subjects = subjects;
        this.subjectSets = subjectSets;
    }

    @GetMapping("/students/{studentId}")
    public List<EnrolmentDto> historyForStudent(@PathVariable UUID studentId) {
        return repo.listByStudent(studentId);
    }

    @GetMapping("/sections/{sectionId}")
    public List<EnrolmentDto> roster(@PathVariable UUID sectionId, @RequestParam(defaultValue = "true") boolean activeOnly) {
        return repo.listBySection(sectionId, activeOnly);
    }

    /**
     * {@code rollNo} is optional — left out, the section's number series issues
     * the next one. {@code overCapacityReason} is required only when the
     * section is already full (GAP-10).
     */
    public record EnrolRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, @NotNull UUID sectionId,
        @NotNull UUID academicYearId, @NotNull LocalDate startsOn, String rollNo, String overCapacityReason
    ) {}

    @PostMapping
    public EnrolmentDto enrol(@RequestBody EnrolRequest req) {
        return repo.enrol(req.schoolId(), req.studentId(), req.sectionId(), req.academicYearId(),
            req.startsOn(), req.rollNo(), req.overCapacityReason());
    }

    public record TransferRequest(@NotNull UUID newSectionId, String rollNo, String overCapacityReason) {}

    @PostMapping("/{id}/transfer")
    public EnrolmentDto transfer(@PathVariable UUID id, @RequestBody TransferRequest req) {
        return repo.transfer(id, req.newSectionId(), req.rollNo(), req.overCapacityReason());
    }

    public record StatusRequest(@NotNull String status, LocalDate endsOn, String reason) {}

    /**
     * Withdrawing, suspending or reinstating a child is the kind of change a
     * family disputes months later, so it carries a reason and an audit entry
     * with the enrolment row before and after (SEC-08).
     */
    @PostMapping("/{id}/status")
    @Audited(action = "enrolment.status_change", targetType = "enrolment")
    public EnrolmentDto setStatus(@PathVariable UUID id, @RequestBody StatusRequest req) {
        return repo.setStatus(id, req.status(), req.endsOn());
    }

    /** Re-sequences a section's roll numbers from 1 in admission order (ENR-02). */
    @PostMapping("/sections/{sectionId}/renumber")
    public List<EnrolmentDto> renumber(@PathVariable UUID sectionId) {
        return repo.renumber(sectionId);
    }

    // -------------------------- Subject election (GAP-05) --------------------------

    /**
     * What the student actually studies: the section's compulsory subjects plus
     * their own elections, as of {@code onDate} (default today).
     */
    @GetMapping("/{id}/subjects")
    public List<StudentSubjectDto> subjectSet(
        @PathVariable UUID id,
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(
            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate onDate
    ) {
        return subjectSets.forEnrolment(id, onDate);
    }

    @GetMapping("/students/{studentId}/subjects")
    public List<StudentSubjectDto> subjectSetForStudent(
        @PathVariable UUID studentId,
        @RequestParam(required = false)
        @org.springframework.format.annotation.DateTimeFormat(
            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate onDate
    ) {
        return subjectSets.forStudent(studentId, onDate);
    }

    @GetMapping("/{id}/elections")
    public List<StudentSubjectDto> elections(@PathVariable UUID id) {
        return subjects.listElections(id);
    }

    public record ElectRequest(@NotNull UUID subjectId, UUID electiveGroupId, LocalDate effectiveFrom) {}

    @PostMapping("/{id}/elections")
    public StudentSubjectDto elect(@PathVariable UUID id, @RequestBody ElectRequest req) {
        return subjects.elect(id, req.subjectId(), req.electiveGroupId(),
            req.effectiveFrom() == null ? LocalDate.now() : req.effectiveFrom());
    }

    public record DropElectionRequest(@NotNull UUID subjectId, LocalDate effectiveTo) {}

    /** Ends an election from a date; the marks earned under it stay. */
    @PostMapping("/{id}/elections/drop")
    public ResponseEntity<Void> dropElection(@PathVariable UUID id, @RequestBody DropElectionRequest req) {
        subjects.drop(id, req.subjectId(), req.effectiveTo() == null ? LocalDate.now() : req.effectiveTo());
        return ResponseEntity.noContent().build();
    }
}
