package com.schoolsoft.tenancy.api;

import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.tenancy.internal.SchoolRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/tenancy")
public class SchoolController {

    private final SchoolRepository repo;
    private final AuditService audit;

    public SchoolController(SchoolRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

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

    // -------------------------- Campus --------------------------

    @GetMapping("/schools/{schoolId}/campuses")
    public List<CampusDto> campuses(@PathVariable UUID schoolId) {
        return repo.listCampuses(schoolId);
    }

    public record CreateCampusRequest(@NotBlank String name, boolean isPrimary) {}

    @PostMapping("/schools/{schoolId}/campuses")
    public CampusDto createCampus(@PathVariable UUID schoolId, @RequestBody CreateCampusRequest req) {
        return repo.createCampus(schoolId, req.name(), req.isPrimary());
    }

    // -------------------------- Academic Year --------------------------

    public record CreateAcademicYearRequest(
        @NotBlank String code, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn, boolean isCurrent
    ) {}

    @PostMapping("/schools/{schoolId}/academic-years")
    public AcademicYearDto createAcademicYear(@PathVariable UUID schoolId, @RequestBody CreateAcademicYearRequest req) {
        return repo.createAcademicYear(schoolId, req.code(), req.startsOn(), req.endsOn(), req.isCurrent());
    }

    public record AcademicYearStatusRequest(@NotBlank String status, UUID actingStaffId, String reason) {}

    /**
     * Drives the year through planning → active → closed, and back out of
     * closed via an explicit, reasoned reopen (GAP-14). Closure is what makes
     * last year's attendance, marks and invoices read-only; reopening is
     * audited so "who let this be edited after closure" has an answer.
     */
    @PostMapping("/academic-years/{academicYearId}/status")
    public AcademicYearDto setAcademicYearStatus(
        @PathVariable UUID academicYearId, @RequestBody AcademicYearStatusRequest req
    ) {
        AcademicYearDto before = repo.findAcademicYear(academicYearId);
        AcademicYearDto after = repo.setAcademicYearStatus(
            academicYearId, req.status(), req.actingStaffId(), req.reason());
        audit.record("academic_year.status_changed", "academic_year", academicYearId,
            java.util.Map.of("status", before.status()),
            java.util.Map.of("status", after.status(), "reason", req.reason() == null ? "" : req.reason()));
        return after;
    }

    // -------------------------- Term --------------------------

    @GetMapping("/academic-years/{academicYearId}/terms")
    public List<TermDto> terms(@PathVariable UUID academicYearId) {
        return repo.listTerms(academicYearId);
    }

    public record CreateTermRequest(
        @NotBlank String code, @NotBlank String name, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn
    ) {}

    @PostMapping("/academic-years/{academicYearId}/terms")
    public TermDto createTerm(@PathVariable UUID academicYearId, @RequestBody CreateTermRequest req) {
        return repo.createTerm(academicYearId, req.code(), req.name(), req.startsOn(), req.endsOn());
    }

    // -------------------------- Grade --------------------------

    public record CreateGradeRequest(@NotBlank String code, @NotBlank String name, int sortOrder) {}

    @PostMapping("/schools/{schoolId}/grades")
    public GradeDto createGrade(@PathVariable UUID schoolId, @RequestBody CreateGradeRequest req) {
        return repo.createGrade(schoolId, req.code(), req.name(), req.sortOrder());
    }

    // -------------------------- Section --------------------------

    public record CreateSectionRequest(
        @NotNull UUID gradeId, @NotNull UUID academicYearId, @NotBlank String code, @NotBlank String name,
        @NotBlank String strategyCode, Integer capacity, UUID campusId
    ) {}

    @PostMapping("/schools/{schoolId}/sections")
    public SectionDto createSection(@PathVariable UUID schoolId, @RequestBody CreateSectionRequest req) {
        return repo.createSection(
            schoolId, req.gradeId(), req.academicYearId(), req.code(), req.name(), req.strategyCode(),
            req.capacity(), req.campusId()
        );
    }

    public record BindCurriculumRequest(@NotNull UUID curriculumId, @NotBlank String strategyCode) {}

    @PutMapping("/sections/{sectionId}/curriculum")
    public ResponseEntity<Void> bindCurriculum(@PathVariable UUID sectionId, @RequestBody BindCurriculumRequest req) {
        repo.bindSectionCurriculum(sectionId, req.curriculumId(), req.strategyCode());
        return ResponseEntity.noContent().build();
    }

    // -------------------------- Subject --------------------------

    @GetMapping("/schools/{schoolId}/subjects")
    public List<SubjectDto> subjects(@PathVariable UUID schoolId) {
        return repo.listSubjects(schoolId);
    }

    public record CreateSubjectRequest(@NotBlank String code, @NotBlank String name, String boardCode) {}

    @PostMapping("/schools/{schoolId}/subjects")
    public SubjectDto createSubject(@PathVariable UUID schoolId, @RequestBody CreateSubjectRequest req) {
        return repo.createSubject(schoolId, req.code(), req.name(), req.boardCode());
    }

    // -------------------------- Section-Subject-Teacher --------------------------

    @GetMapping("/sections/{sectionId}/teachers")
    public List<SectionSubjectTeacherDto> sectionTeachers(@PathVariable UUID sectionId) {
        return repo.listSectionSubjectTeachers(sectionId);
    }

    /**
     * {@code isElective} marks a subject the section is taught but only its
     * electing students take — the flag {@code SubjectSetResolver} reads to
     * tell a section-wide subject from an option (GAP-05).
     */
    public record AssignTeacherRequest(
        @NotNull UUID subjectId, @NotNull UUID teacherStaffId, boolean isPrimary, boolean isElective
    ) {}

    @PostMapping("/sections/{sectionId}/teachers")
    public SectionSubjectTeacherDto assignTeacher(@PathVariable UUID sectionId, @RequestBody AssignTeacherRequest req) {
        return repo.assignSectionSubjectTeacher(
            sectionId, req.subjectId(), req.teacherStaffId(), req.isPrimary(), req.isElective());
    }

    // -------------------------- Elective groups --------------------------

    @GetMapping("/schools/{schoolId}/elective-groups")
    public List<ElectiveGroupDto> electiveGroups(
        @PathVariable UUID schoolId,
        @RequestParam(required = false) UUID academicYearId,
        @RequestParam(required = false) UUID gradeId
    ) {
        return repo.listElectiveGroups(schoolId, academicYearId, gradeId);
    }

    public record CreateElectiveGroupRequest(
        @NotNull UUID academicYearId, @NotNull UUID gradeId, @NotBlank String code, @NotBlank String name,
        int minPicks, int maxPicks, @NotNull List<UUID> subjectIds
    ) {}

    @PostMapping("/schools/{schoolId}/elective-groups")
    public ElectiveGroupDto createElectiveGroup(
        @PathVariable UUID schoolId, @RequestBody CreateElectiveGroupRequest req
    ) {
        return repo.createElectiveGroup(schoolId, req.academicYearId(), req.gradeId(), req.code(), req.name(),
            req.minPicks() == 0 ? 1 : req.minPicks(), req.maxPicks() == 0 ? 1 : req.maxPicks(), req.subjectIds());
    }
}
