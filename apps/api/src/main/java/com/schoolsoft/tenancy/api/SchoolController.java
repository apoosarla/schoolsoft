package com.schoolsoft.tenancy.api;

import com.schoolsoft.tenancy.internal.SchoolRepository;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/tenancy")
public class SchoolController {

    private final SchoolRepository repo;
    public SchoolController(SchoolRepository repo) { this.repo = repo; }

    @GetMapping("/schools")
    public List<SchoolDto> list() { return repo.list(); }

    @GetMapping("/schools/{id}")
    public ResponseEntity<SchoolDto> get(@PathVariable UUID id) {
        return repo.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    public record CreateSchoolRequest(
        @NotBlank String slug,
        @NotBlank String name,
        @NotBlank String boardCode,
        String gstin,
        String stateCode
    ) {}

    @PostMapping("/schools")
    public SchoolDto create(@RequestBody CreateSchoolRequest req) {
        return repo.create(req.slug(), req.name(), req.boardCode(), req.gstin(), req.stateCode());
    }

    @GetMapping("/schools/{schoolId}/academic-years")
    public List<AcademicYearDto> academicYears(@PathVariable UUID schoolId) {
        return repo.listAcademicYears(schoolId);
    }

    @GetMapping("/schools/{schoolId}/grades")
    public List<GradeDto> grades(@PathVariable UUID schoolId) {
        return repo.listGrades(schoolId);
    }

    @GetMapping("/schools/{schoolId}/sections")
    public List<SectionDto> sections(@PathVariable UUID schoolId, @RequestParam(required = false) UUID academicYearId) {
        return repo.listSections(schoolId, academicYearId);
    }
}
