package com.schoolsoft.assessment.api;

import com.schoolsoft.assessment.internal.AssessmentRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/assessment")
public class AssessmentController {

    private final AssessmentRepository repo;
    public AssessmentController(AssessmentRepository repo) { this.repo = repo; }

    @GetMapping
    public List<AssessmentDto> listBySection(@RequestParam UUID sectionId) {
        return repo.listBySection(sectionId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AssessmentDto> get(@PathVariable UUID id) {
        return repo.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    public record CreateAssessmentRequest(
        @NotNull UUID schoolId, @NotNull UUID sectionId, @NotNull UUID subjectId, UUID termId,
        @NotBlank String strategyCode, @NotBlank String name, @NotBlank String assessmentType,
        Double maxMarks, Double weightPct, LocalDate scheduledOn
    ) {}

    @PostMapping
    public AssessmentDto create(@RequestBody CreateAssessmentRequest req) {
        return repo.create(
            req.schoolId(), req.sectionId(), req.subjectId(), req.termId(), req.strategyCode(),
            req.name(), req.assessmentType(), req.maxMarks(), req.weightPct(), req.scheduledOn()
        );
    }

    public record StatusRequest(@NotBlank String status) {}

    @PostMapping("/{id}/status")
    public AssessmentDto setStatus(@PathVariable UUID id, @RequestBody StatusRequest req) {
        return repo.setStatus(id, req.status());
    }

    // -------------------------- Components --------------------------

    @GetMapping("/{id}/components")
    public List<AssessmentComponentDto> components(@PathVariable UUID id) {
        return repo.listComponents(id);
    }

    public record CreateComponentRequest(
        @NotBlank String code, @NotBlank String name, double maxMarks, Double weightPct, int sortOrder
    ) {}

    @PostMapping("/{id}/components")
    public AssessmentComponentDto addComponent(@PathVariable UUID id, @RequestBody CreateComponentRequest req) {
        return repo.addComponent(id, req.code(), req.name(), req.maxMarks(), req.weightPct(), req.sortOrder());
    }

    // -------------------------- Marks --------------------------

    @GetMapping("/components/{componentId}/marks")
    public List<MarkDto> marks(@PathVariable UUID componentId) {
        return repo.listMarks(componentId);
    }

    public record EnterMarkRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, Double rawMarks, String gradeLetter,
        String remarks, boolean isAbsent, UUID enteredByStaffId
    ) {}

    @PostMapping("/components/{componentId}/marks")
    public MarkDto enterMark(@PathVariable UUID componentId, @RequestBody EnterMarkRequest req) {
        return repo.enterMark(
            req.schoolId(), componentId, req.studentId(), req.rawMarks(),
            req.gradeLetter(), req.remarks(), req.isAbsent(), req.enteredByStaffId()
        );
    }

    // -------------------------- Report Cards --------------------------

    @GetMapping("/report-cards/students/{studentId}")
    public List<ReportCardDto> reportCards(@PathVariable UUID studentId) {
        return repo.listReportCards(studentId);
    }

    public record GenerateReportCardRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, @NotNull UUID academicYearId, UUID termId,
        @NotBlank String strategyCode, @NotBlank String templateCode, Map<String, Object> payload
    ) {}

    @PostMapping("/report-cards")
    public ReportCardDto generateReportCard(@RequestBody GenerateReportCardRequest req) {
        return repo.generateReportCard(
            req.schoolId(), req.studentId(), req.academicYearId(), req.termId(),
            req.strategyCode(), req.templateCode(), req.payload()
        );
    }

    @PostMapping("/report-cards/{id}/lock")
    public ReportCardDto lockReportCard(@PathVariable UUID id) {
        return repo.lockReportCard(id);
    }
}
