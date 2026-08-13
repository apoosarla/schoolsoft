package com.schoolsoft.assessment.api;

import com.schoolsoft.assessment.internal.AssessmentPolicyRepository;
import com.schoolsoft.assessment.internal.AssessmentRepository;
import com.schoolsoft.assessment.internal.MarkService;
import com.schoolsoft.assessment.internal.ReportCardService;
import com.schoolsoft.audit.api.Audited;
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
    private final MarkService marks;
    private final ReportCardService reportCards;
    private final AssessmentPolicyRepository policies;

    public AssessmentController(AssessmentRepository repo, MarkService marks, ReportCardService reportCards,
                                AssessmentPolicyRepository policies) {
        this.repo = repo;
        this.marks = marks;
        this.reportCards = reportCards;
        this.policies = policies;
    }

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

    public record StatusRequest(@NotBlank String status, String reason) {}

    /**
     * Moving an assessment back out of {@code locked} or {@code published}
     * reopens marks a family has already seen, so it needs a reason and an
     * authorised role — and it is audited either way (SEC-08). Moving it
     * forward into marking runs the weight check (ASMT-03).
     */
    @PostMapping("/{id}/status")
    @Audited(action = "assessment.status_change", targetType = "assessment", requireReason = false)
    public AssessmentDto setStatus(@PathVariable UUID id, @RequestBody StatusRequest req) {
        return repo.setStatus(id, req.status(), req.reason());
    }

    /** What is wrong with the assessment's shape before marking opens (ASMT-03). */
    @GetMapping("/{id}/validation")
    public AssessmentRepository.Validation validate(@PathVariable UUID id) {
        return repo.validate(id);
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
        return marks.listMarks(componentId);
    }

    /**
     * {@code status} names why there is no number: {@code pending} for an
     * unmarked paper, {@code absent}, {@code medical_leave} or {@code exempt}
     * for a paper that will never have one. Omit it and the submission speaks
     * for itself — a number is a mark, a blank is pending. {@code isAbsent}
     * remains accepted for the apps that predate the status.
     */
    public record EnterMarkRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, Double rawMarks, String status, String gradeLetter,
        String remarks, boolean isAbsent, String reason, UUID enteredByStaffId
    ) {
        String effectiveStatus() {
            if (status != null && !status.isBlank()) return status;
            return isAbsent ? "absent" : null;
        }
    }

    @PostMapping("/components/{componentId}/marks")
    public MarkDto enterMark(@PathVariable UUID componentId, @RequestBody EnterMarkRequest req) {
        return marks.enter(req.schoolId(), componentId,
            new MarkService.MarkEntry(req.studentId(), req.rawMarks(), req.effectiveStatus(),
                req.gradeLetter(), req.remarks()),
            req.enteredByStaffId(), req.reason());
    }

    public record BulkMarkEntry(
        @NotNull UUID studentId, Double rawMarks, String status, String gradeLetter, String remarks
    ) {}

    public record BulkMarkRequest(
        @NotNull UUID schoolId, @NotNull UUID componentId, @NotNull List<BulkMarkEntry> entries,
        UUID enteredByStaffId, String reason
    ) {}

    /**
     * A whole section in one call. Rows are validated individually: a mark above
     * the component maximum is rejected and named, and the rest are stored
     * (ASMT-04).
     */
    @PostMapping("/marks/bulk")
    public MarkService.BulkResult enterMarksInBulk(@RequestBody BulkMarkRequest req) {
        return marks.enterBulk(req.schoolId(), req.componentId(),
            req.entries().stream()
                .map(e -> new MarkService.MarkEntry(e.studentId(), e.rawMarks(), e.status(),
                    e.gradeLetter(), e.remarks()))
                .toList(),
            req.enteredByStaffId(), req.reason());
    }

    /** What this mark used to be, and why it changed (ASMT-07, ASMT-08). */
    @GetMapping("/marks/{markId}/revisions")
    public List<MarkRevisionDto> revisions(@PathVariable UUID markId) {
        return marks.revisions(markId);
    }

    // -------------------------- Re-evaluation --------------------------

    public record ReevaluationRequest(@NotBlank String reason) {}

    /** Raised by a guardian for their own child, or by the school on their behalf. */
    @PostMapping("/marks/{markId}/re-evaluations")
    public MarkReevaluationDto requestReevaluation(@PathVariable UUID markId,
                                                   @RequestBody ReevaluationRequest req) {
        return marks.requestReevaluation(markId, req.reason());
    }

    @GetMapping("/re-evaluations")
    public List<MarkReevaluationDto> reevaluations(@RequestParam UUID studentId) {
        return marks.reevaluationsForStudent(studentId);
    }

    public record ReevaluationDecision(@NotBlank String outcome, Double newRawMarks, @NotBlank String reason) {}

    /**
     * {@code revised} supersedes the mark and keeps the original; {@code upheld}
     * and {@code rejected} change nothing but record that somebody looked.
     */
    @PostMapping("/re-evaluations/{id}/decide")
    @Audited(action = "mark.re_evaluation_decided", targetType = "mark_reevaluation")
    public MarkReevaluationDto decideReevaluation(@PathVariable UUID id, @RequestBody ReevaluationDecision req) {
        return marks.decideReevaluation(id, req.outcome(), req.newRawMarks(), req.reason());
    }

    // -------------------------- Report Cards --------------------------

    @GetMapping("/report-cards/students/{studentId}")
    public List<ReportCardDto> reportCards(@PathVariable UUID studentId) {
        return reportCards.listForStudent(studentId);
    }

    /** The card as it is rendered: subject rows, co-scholastic ratings, remarks. */
    @GetMapping("/report-cards/{id}")
    public ReportCardDetailDto reportCard(@PathVariable UUID id) {
        return reportCards.detail(id);
    }

    public record CoScholasticRequest(String areaCode, String areaName, String rating, String remarks, int sortOrder) {}

    public record GenerateReportCardRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, @NotNull UUID academicYearId, UUID termId,
        @NotBlank String strategyCode, @NotBlank String templateCode, Map<String, Object> payload,
        String teacherRemarks, String principalRemarks, String promotionDecision,
        List<CoScholasticRequest> coScholastic
    ) {}

    @PostMapping("/report-cards")
    public ReportCardDto generateReportCard(@RequestBody GenerateReportCardRequest req) {
        return reportCards.generate(new ReportCardService.GenerateRequest(
            req.schoolId(), req.studentId(), req.academicYearId(), req.termId(), req.strategyCode(),
            req.templateCode(), req.payload(), req.teacherRemarks(), req.principalRemarks(),
            req.promotionDecision(),
            req.coScholastic() == null ? List.of() : req.coScholastic().stream()
                .map(c -> new ReportCardService.CoScholasticInput(c.areaCode(), c.areaName(), c.rating(),
                    c.remarks(), c.sortOrder()))
                .toList()));
    }

    public record GenerateSectionRequest(
        @NotNull UUID schoolId, @NotNull UUID sectionId, @NotNull UUID academicYearId, UUID termId,
        @NotBlank String strategyCode, @NotBlank String templateCode
    ) {}

    /** A section's cards in one run — and the rank across them (ASMT-10, ASMT-11). */
    @PostMapping("/report-cards/sections")
    public List<ReportCardDto> generateForSection(@RequestBody GenerateSectionRequest req) {
        return reportCards.generateForSection(req.schoolId(), req.sectionId(), req.academicYearId(),
            req.termId(), req.strategyCode(), req.templateCode());
    }

    @PostMapping("/report-cards/{id}/lock")
    @Audited(action = "report_card.locked", targetType = "report_card", requireReason = false)
    public ReportCardDto lockReportCard(@PathVariable UUID id) {
        return reportCards.lock(id);
    }

    public record UnlockRequest(@NotBlank String reason) {}

    @PostMapping("/report-cards/{id}/unlock")
    @Audited(action = "report_card.unlocked", targetType = "report_card")
    public ReportCardDto unlockReportCard(@PathVariable UUID id, @RequestBody UnlockRequest req) {
        return reportCards.unlock(id, req.reason());
    }

    /** Publication is what a family sees, and where the dues policy applies (ASMT-15). */
    @PostMapping("/report-cards/{id}/publish")
    @Audited(action = "report_card.published", targetType = "report_card", requireReason = false)
    public ReportCardDto publishReportCard(@PathVariable UUID id) {
        return reportCards.publish(id);
    }

    public record PromotionRequest(@NotBlank String decision) {}

    /** The school's own call, overriding what the strategy suggested (GAP-02 feeds on this). */
    @PostMapping("/report-cards/{id}/promotion")
    @Audited(action = "report_card.promotion_decision", targetType = "report_card", requireReason = false)
    public ReportCardDto setPromotion(@PathVariable UUID id, @RequestBody PromotionRequest req) {
        return reportCards.setPromotion(id, req.decision());
    }

    // -------------------------- Policy --------------------------

    @GetMapping("/policy")
    public AssessmentPolicyRepository.Policy policy(@RequestParam UUID schoolId) {
        return policies.forSchool(schoolId);
    }

    public record PolicyRequest(
        @NotNull UUID schoolId, String duesBlockPolicy, Double duesBlockThreshold, Double weightTolerancePct
    ) {}

    @PutMapping("/policy")
    public AssessmentPolicyRepository.Policy setPolicy(@RequestBody PolicyRequest req) {
        return policies.upsert(req.schoolId(), req.duesBlockPolicy(), req.duesBlockThreshold(),
            req.weightTolerancePct());
    }
}
