package com.schoolsoft.admissions.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.admissions.internal.AdmissionsRepository;
import com.schoolsoft.platform.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/admissions")
public class AdmissionsController {

    private final AdmissionsRepository repo;
    public AdmissionsController(AdmissionsRepository repo) { this.repo = repo; }

    @PreAuthorize("@perm.can('admission.view')")
    @GetMapping("/applications")
    public List<AdmissionApplicationDto> list(@RequestParam UUID schoolId, @RequestParam(required = false) String state) {
        return repo.list(schoolId, state);
    }

    @PreAuthorize("@perm.can('admission.view')")
    @GetMapping("/applications/{id}")
    public ResponseEntity<AdmissionApplicationDto> get(@PathVariable UUID id) {
        return repo.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    public record CreateApplicationRequest(
        @NotNull UUID schoolId, @NotNull UUID academicYearId, @NotNull UUID gradeId, @NotBlank String applicationNo,
        @NotBlank String applicantFirstName, String applicantLastName, LocalDate applicantDob, String applicantGender,
        @NotBlank String guardianName, @NotBlank String guardianPhone, String guardianEmail, String source
    ) {}

    @PreAuthorize("@perm.can('admission.manage')")
    @PostMapping("/applications")
    public AdmissionApplicationDto create(@RequestBody CreateApplicationRequest req) {
        return repo.create(
            req.schoolId(), req.academicYearId(), req.gradeId(), req.applicationNo(),
            req.applicantFirstName(), req.applicantLastName(), req.applicantDob(), req.applicantGender(),
            req.guardianName(), req.guardianPhone(), req.guardianEmail(), req.source()
        );
    }

    public record TransitionRequest(@NotBlank String toState) {}

    @PreAuthorize("@perm.can('admission.decide')")
    @PostMapping("/applications/{id}/transition")
    public AdmissionApplicationDto transition(@PathVariable UUID id, @RequestBody TransitionRequest req) {
        var snap = TenantContext.get();
        UUID actor = snap == null ? null : snap.userAccountId();
        return repo.transition(id, req.toState(), actor);
    }

    public record TestScoreRequest(double score, String notes) {}

    @PreAuthorize("@perm.can('admission.manage')")
    @PostMapping("/applications/{id}/test-score")
    public AdmissionApplicationDto testScore(@PathVariable UUID id, @RequestBody TestScoreRequest req) {
        return repo.recordTestScore(id, req.score(), req.notes());
    }

    @PreAuthorize("@perm.can('admission.view')")
    @GetMapping("/applications/{id}/events")
    public List<AdmissionEventDto> events(@PathVariable UUID id) {
        return repo.listEvents(id);
    }

    public record ConvertRequest(@NotNull UUID sectionId, String rollNo, String overCapacityReason) {}

    @PreAuthorize("@perm.can('admission.enrol')")
    @PostMapping("/applications/{id}/enrol")
    public Map<String, UUID> enrol(@PathVariable UUID id, @RequestBody ConvertRequest req) {
        UUID studentId = repo.convertToStudent(id, req.sectionId(), req.rollNo(), req.overCapacityReason());
        return Map.of("studentId", studentId);
    }
}
