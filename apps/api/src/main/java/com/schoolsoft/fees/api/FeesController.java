package com.schoolsoft.fees.api;

import com.schoolsoft.fees.internal.DunningService;
import com.schoolsoft.fees.internal.FeeAdjustmentService;
import com.schoolsoft.fees.internal.FeeGenerationService;
import com.schoolsoft.fees.internal.FeeReportRepository;
import com.schoolsoft.fees.internal.FeeStructureRepository;
import com.schoolsoft.fees.internal.FeesRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/fees")
public class FeesController {

    private final FeesRepository repo;
    private final FeeStructureRepository structures;
    private final FeeGenerationService generation;
    private final FeeAdjustmentService adjustments;
    private final FeeReportRepository reports;
    private final DunningService dunning;

    public FeesController(FeesRepository repo, FeeStructureRepository structures,
                          FeeGenerationService generation, FeeAdjustmentService adjustments,
                          FeeReportRepository reports, DunningService dunning) {
        this.repo = repo;
        this.structures = structures;
        this.generation = generation;
        this.adjustments = adjustments;
        this.reports = reports;
        this.dunning = dunning;
    }

    // -------------------------- Structures (FEE-01) --------------------------

    @GetMapping("/structures")
    public List<FeeStructureDto> structures(
        @RequestParam UUID schoolId,
        @RequestParam(required = false) UUID academicYearId,
        @RequestParam(required = false) UUID gradeId
    ) {
        return structures.list(schoolId, academicYearId, gradeId);
    }

    @GetMapping("/structures/{id}")
    public FeeStructureDto structure(@PathVariable UUID id) {
        return structures.find(id);
    }

    public record StructureLineRequest(@NotNull UUID feeHeadId, double amount) {}

    public record CreateStructureRequest(
        @NotNull UUID schoolId, @NotNull UUID gradeId, @NotNull UUID academicYearId, @NotBlank String name,
        java.util.Map<String, Object> schedule, @NotNull List<StructureLineRequest> lines
    ) {}

    @PostMapping("/structures")
    public FeeStructureDto createStructure(@RequestBody CreateStructureRequest req) {
        return structures.create(req.schoolId(), req.gradeId(), req.academicYearId(), req.name(),
            req.schedule(), req.lines().stream()
                .map(l -> new FeeStructureRepository.LineInput(l.feeHeadId(), l.amount())).toList());
    }

    public record ReplaceLinesRequest(@NotNull List<StructureLineRequest> lines) {}

    @PutMapping("/structures/{id}/lines")
    public FeeStructureDto replaceStructureLines(@PathVariable UUID id, @RequestBody ReplaceLinesRequest req) {
        return structures.replaceLines(id, req.lines().stream()
            .map(l -> new FeeStructureRepository.LineInput(l.feeHeadId(), l.amount())).toList());
    }

    public record CloneStructureRequest(@NotNull UUID targetAcademicYearId, String name) {}

    /** Next year's structure is a copy — editing it must not touch this year's. */
    @PostMapping("/structures/{id}/clone")
    public FeeStructureDto cloneStructure(@PathVariable UUID id, @RequestBody CloneStructureRequest req) {
        return structures.cloneInto(id, req.targetAcademicYearId(), req.name());
    }

    // -------------------------- Concessions (FEE-03) --------------------------

    public record ConcessionRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, @NotNull UUID academicYearId, @NotBlank String kind,
        Double pct, Double flatAmount, UUID appliesToHeadId, String notes, UUID approvedByStaffId
    ) {}

    /**
     * A concession is a decision about one child's bill, so it is recorded
     * against them with who approved it — and it shows on the invoice as a
     * discount line rather than a quietly smaller number (FEE-03).
     */
    @PostMapping("/concessions")
    public java.util.Map<String, Object> grantConcession(@RequestBody ConcessionRequest req) {
        UUID id = repo.grantConcession(req.schoolId(), req.studentId(), req.academicYearId(), req.kind(),
            req.pct(), req.flatAmount(), req.appliesToHeadId(), req.notes(), req.approvedByStaffId());
        return java.util.Map.of("id", id);
    }

    @GetMapping("/concessions")
    public List<java.util.Map<String, Object>> concessions(
        @RequestParam UUID studentId, @RequestParam(required = false) UUID academicYearId
    ) {
        return repo.listConcessions(studentId, academicYearId);
    }

    public record SiblingPolicyRequest(
        @NotNull UUID schoolId, @NotNull UUID academicYearId, int nthChild, double pct, UUID appliesToHeadId
    ) {}

    @PostMapping("/sibling-policies")
    public java.util.Map<String, Object> siblingPolicy(@RequestBody SiblingPolicyRequest req) {
        UUID id = repo.upsertSiblingPolicy(req.schoolId(), req.academicYearId(), req.nthChild(), req.pct(),
            req.appliesToHeadId());
        return java.util.Map.of("id", id);
    }

    public record DunningPolicyRequest(
        @NotNull UUID schoolId, int graceDays, List<Integer> reminderDays, Double lateFeePct,
        Double lateFeeFlat, UUID lateFeeHeadId
    ) {}

    @PutMapping("/dunning-policy")
    public java.util.Map<String, Object> dunningPolicy(@RequestBody DunningPolicyRequest req) {
        UUID id = repo.upsertDunningPolicy(req.schoolId(), req.graceDays(), req.reminderDays(),
            req.lateFeePct(), req.lateFeeFlat(), req.lateFeeHeadId());
        return java.util.Map.of("id", id);
    }

    // -------------------------- Generation (FEE-02) --------------------------

    public record GenerateRequest(
        @NotNull UUID schoolId, @NotNull UUID academicYearId, UUID gradeId, @NotBlank String cycleLabel,
        @NotNull LocalDate dueOn, UUID runByStaffId
    ) {}

    @PostMapping("/generate")
    public FeeGenerationService.RunResult generate(@RequestBody GenerateRequest req) {
        return generation.generate(req.schoolId(), req.academicYearId(), req.gradeId(), req.cycleLabel(),
            req.dueOn(), req.runByStaffId());
    }

    // -------------------------- Families (FEE-04, ADM-11) --------------------------

    public record FamilyRequest(@NotNull UUID schoolId, @NotNull UUID studentId) {}

    /** Groups a student with their siblings, by their shared guardian. */
    @PostMapping("/families/link")
    public java.util.Map<String, Object> linkFamily(@RequestBody FamilyRequest req) {
        UUID familyId = generation.familyForStudent(req.schoolId(), req.studentId());
        return java.util.Map.of("familyId", familyId);
    }

    public record CombinedInvoiceRequest(
        @NotNull UUID schoolId, @NotNull UUID familyId, @NotBlank String cycleLabel, @NotNull LocalDate dueOn
    ) {}

    @PostMapping("/families/combined-invoice")
    public FeeInvoiceDto combinedInvoice(@RequestBody CombinedInvoiceRequest req) {
        UUID invoiceId = generation.createCombinedFamilyInvoice(
            req.schoolId(), req.familyId(), req.cycleLabel(), req.dueOn());
        return repo.findInvoice(invoiceId).orElseThrow();
    }

    // -------------------------- Adjustments (FEE-08/10/11) --------------------------

    @GetMapping("/invoices/{id}/adjustments")
    public List<FeeAdjustmentDto> adjustments(@PathVariable UUID id) {
        return adjustments.listForInvoice(id);
    }

    public record AdjustRequest(
        @NotNull UUID schoolId, @NotBlank String kind, double amount, @NotBlank String reason,
        UUID paymentId, UUID approvedByStaffId, UUID feeHeadId
    ) {}

    /** credit_note | refund | waiver | late_fee | charge | reversal. */
    @PostMapping("/invoices/{id}/adjustments")
    public FeeAdjustmentDto adjust(@PathVariable UUID id, @RequestBody AdjustRequest req) {
        return adjustments.adjust(req.schoolId(), id, req.kind(), req.amount(), req.reason(),
            req.paymentId(), req.approvedByStaffId(), req.feeHeadId());
    }

    // -------------------------- Dunning (FEE-09/10) --------------------------

    public record DunningRunRequest(@NotNull UUID schoolId, LocalDate asOf) {}

    @PostMapping("/dunning/run")
    public DunningService.Result runDunning(@RequestBody DunningRunRequest req) {
        return dunning.runFor(req.schoolId(), req.asOf() == null ? LocalDate.now() : req.asOf());
    }

    // -------------------------- Reports (FEE-14/15) --------------------------

    @GetMapping("/reports/day-book")
    public java.util.Map<String, Object> dayBook(
        @RequestParam UUID schoolId, @RequestParam LocalDate from, @RequestParam LocalDate to
    ) {
        return reports.dayBook(schoolId, from, to);
    }

    @GetMapping("/reports/outstanding")
    public java.util.Map<String, Object> outstanding(
        @RequestParam UUID schoolId,
        @RequestParam(required = false) UUID academicYearId,
        @RequestParam(required = false) UUID gradeId,
        @RequestParam(required = false) UUID sectionId
    ) {
        return reports.outstanding(schoolId, academicYearId, gradeId, sectionId);
    }

    @GetMapping("/students/{studentId}/dues")
    public java.util.Map<String, Object> dues(@PathVariable UUID studentId) {
        return reports.duesFor(studentId);
    }

    // -------------------------- Heads --------------------------

    @GetMapping("/heads")
    public List<FeeHeadDto> heads(@RequestParam UUID schoolId) {
        return repo.listHeads(schoolId);
    }

    public record CreateHeadRequest(
        @NotBlank String code, @NotBlank String name, boolean isRecurring, double gstRatePct, String hsnSac
    ) {}

    @PostMapping("/heads")
    public FeeHeadDto createHead(@RequestParam UUID schoolId, @RequestBody CreateHeadRequest req) {
        return repo.createHead(schoolId, req.code(), req.name(), req.isRecurring(), req.gstRatePct(), req.hsnSac());
    }

    // -------------------------- Invoices --------------------------

    @GetMapping("/invoices")
    public List<FeeInvoiceDto> invoicesForStudent(@RequestParam UUID studentId) {
        return repo.listInvoicesByStudent(studentId);
    }

    @GetMapping("/invoices/{id}")
    public ResponseEntity<FeeInvoiceDto> getInvoice(@PathVariable UUID id) {
        return repo.findInvoice(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/invoices/{id}/lines")
    public List<FeeInvoiceLineDto> invoiceLines(@PathVariable UUID id) {
        return repo.listInvoiceLines(id);
    }

    public record InvoiceLineRequest(@NotNull UUID feeHeadId, String description, double amount, double discount, double gst) {}

    public record CreateInvoiceRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, @NotBlank String invoiceNo, @NotBlank String cycleLabel,
        @NotNull LocalDate dueOn, @NotNull List<InvoiceLineRequest> lines
    ) {}

    @PostMapping("/invoices")
    public FeeInvoiceDto createInvoice(@RequestBody CreateInvoiceRequest req) {
        var lines = req.lines().stream()
            .map(l -> new FeesRepository.InvoiceLineInput(l.feeHeadId(), l.description(), l.amount(), l.discount(), l.gst()))
            .toList();
        return repo.createInvoice(req.schoolId(), req.studentId(), req.invoiceNo(), req.cycleLabel(), req.dueOn(), lines);
    }

    // -------------------------- Payments --------------------------

    @GetMapping("/invoices/{id}/payments")
    public List<PaymentDto> payments(@PathVariable UUID id) {
        return repo.listPaymentsForInvoice(id);
    }

    public record RecordPaymentRequest(
        @NotNull UUID schoolId, @NotNull UUID feeInvoiceId, double amount, @NotBlank String gateway,
        String method, @NotBlank String idempotencyKey
    ) {}

    @PostMapping("/payments")
    public PaymentDto recordPayment(@RequestBody RecordPaymentRequest req) {
        return repo.recordPayment(req.schoolId(), req.feeInvoiceId(), req.amount(), req.gateway(), req.method(), req.idempotencyKey());
    }

    // -------------------------- Ledger --------------------------

    @GetMapping("/ledger")
    public List<LedgerEntryDto> ledger(@RequestParam String sourceType, @RequestParam UUID sourceId) {
        return repo.ledgerForSource(sourceType, sourceId);
    }
}
