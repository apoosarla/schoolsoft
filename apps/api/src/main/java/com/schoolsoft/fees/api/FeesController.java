package com.schoolsoft.fees.api;

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
    public FeesController(FeesRepository repo) { this.repo = repo; }

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
