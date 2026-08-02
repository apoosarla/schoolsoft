package com.schoolsoft.enrolment.api;

import com.schoolsoft.enrolment.internal.EnrolmentRepository;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/enrolment")
public class EnrolmentController {

    private final EnrolmentRepository repo;
    public EnrolmentController(EnrolmentRepository repo) { this.repo = repo; }

    @GetMapping("/students/{studentId}")
    public List<EnrolmentDto> historyForStudent(@PathVariable UUID studentId) {
        return repo.listByStudent(studentId);
    }

    @GetMapping("/sections/{sectionId}")
    public List<EnrolmentDto> roster(@PathVariable UUID sectionId, @RequestParam(defaultValue = "true") boolean activeOnly) {
        return repo.listBySection(sectionId, activeOnly);
    }

    public record EnrolRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, @NotNull UUID sectionId,
        @NotNull UUID academicYearId, @NotNull LocalDate startsOn, String rollNo
    ) {}

    @PostMapping
    public EnrolmentDto enrol(@RequestBody EnrolRequest req) {
        return repo.enrol(req.schoolId(), req.studentId(), req.sectionId(), req.academicYearId(), req.startsOn(), req.rollNo());
    }

    public record TransferRequest(@NotNull UUID newSectionId, String rollNo) {}

    @PostMapping("/{id}/transfer")
    public EnrolmentDto transfer(@PathVariable UUID id, @RequestBody TransferRequest req) {
        return repo.transfer(id, req.newSectionId(), req.rollNo());
    }

    public record StatusRequest(@NotNull String status, LocalDate endsOn) {}

    @PostMapping("/{id}/status")
    public EnrolmentDto setStatus(@PathVariable UUID id, @RequestBody StatusRequest req) {
        return repo.setStatus(id, req.status(), req.endsOn());
    }
}
