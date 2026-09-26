package com.schoolsoft.tenancy.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ForbiddenException;
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
    private final AcademicYearLifecycle years;

    public SchoolController(SchoolRepository repo, AuditService audit, AcademicYearLifecycle years) {
        this.repo = repo;
        this.audit = audit;
        this.years = years;
    }

    @PreAuthorize("@perm.can('structure.view')")
    @GetMapping("/schools")
    public List<SchoolDto> list() { return repo.list(); }

    @PreAuthorize("@perm.can('structure.view')")
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

    /**
     * Creating a school was platform-admin work, which made a chain wait on a
     * ticket to Schoolsoft to open its own. {@code school.onboard} opens it to
     * the chain's own HQ admin — the one write in an otherwise read-only
     * baseline, argued for in {@code PermissionChecker}. A platform admin
     * still passes: they hold every permission there is.
     *
     * <h2>Why a principal is refused here and not elsewhere</h2>
     * A head of school holds {@code school.onboard} too, because it is also
     * what opens their own school once its setup is done. It cannot create
     * one: V009's policy on {@code school} carries
     * {@code WITH CHECK (… OR id = current_school_id())}, so a session scoped
     * to a school can only write that school's row. Without this guard the
     * database refuses the INSERT and the caller gets a 500 about nothing they
     * can act on; with it they get the sentence that explains where the act
     * actually belongs. The rule is the RLS policy's, not this method's — this
     * only says it out loud.
     */
    @PreAuthorize("@perm.can('school.onboard')")
    @PostMapping("/schools")
    public SchoolDto create(@RequestBody CreateSchoolRequest req) {
        var snap = TenantContext.get();
        if (snap != null && snap.schoolId() != null && !snap.trusted()) {
            throw new ForbiddenException(
                "A school is created by the chain, not from inside another school. "
                + "This session is scoped to one school, and row-level security lets it write "
                + "only that school's row — open the new school from the chain's HQ console.");
        }
        return repo.create(req.slug(), req.name(), req.boardCode(), req.gstin(), req.stateCode());
    }

    @PreAuthorize("@perm.can('structure.view')")
    @GetMapping("/schools/{schoolId}/academic-years")
    public List<AcademicYearDto> academicYears(@PathVariable UUID schoolId) {
        return repo.listAcademicYears(schoolId);
    }

    @PreAuthorize("@perm.can('structure.view')")
    @GetMapping("/schools/{schoolId}/grades")
    public List<GradeDto> grades(@PathVariable UUID schoolId) {
        return repo.listGrades(schoolId);
    }

    @PreAuthorize("@perm.can('structure.view')")
    @GetMapping("/schools/{schoolId}/sections")
    public List<SectionDto> sections(@PathVariable UUID schoolId, @RequestParam(required = false) UUID academicYearId) {
        return repo.listSections(schoolId, academicYearId);
    }

    // -------------------------- Campus --------------------------

    @PreAuthorize("@perm.can('structure.view')")
    @GetMapping("/schools/{schoolId}/campuses")
    public List<CampusDto> campuses(@PathVariable UUID schoolId) {
        return repo.listCampuses(schoolId);
    }

    public record CreateCampusRequest(@NotBlank String name, boolean isPrimary) {}

    @PreAuthorize("@perm.can('structure.manage')")
    @PostMapping("/schools/{schoolId}/campuses")
    public CampusDto createCampus(@PathVariable UUID schoolId, @RequestBody CreateCampusRequest req) {
        return repo.createCampus(schoolId, req.name(), req.isPrimary());
    }

    // -------------------------- Academic Year --------------------------

    public record CreateAcademicYearRequest(
        @NotBlank String code, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn, boolean isCurrent
    ) {}

    @PreAuthorize("@perm.can('structure.manage')")
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
    @PreAuthorize("@perm.can('academic_year.manage')")
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

    public record ActivateAcademicYearRequest(UUID actingStaffId) {}

    /**
     * Makes this the school's current year — the flip every "this year" read
     * asks about.
     *
     * <p>Creating a year can do it in the same act, and the rollover wizard
     * does it at the end of the roll. Neither reaches a school in its first
     * year that created one without ticking the box: there is no year to roll
     * from, and the year already exists so creating it again is not available.
     * That school could see the step it was failing and had no screen to fix
     * it with, which is why this is its own door rather than a flag on the
     * status endpoint — the status and the current year are separate
     * questions, and {@code active} is not {@code current}.</p>
     */
    @PreAuthorize("@perm.can('academic_year.manage')")
    @PostMapping("/academic-years/{academicYearId}/activate")
    public AcademicYearDto activateAcademicYear(
        @PathVariable UUID academicYearId, @RequestBody(required = false) ActivateAcademicYearRequest req
    ) {
        return years.activate(academicYearId, req == null ? null : req.actingStaffId());
    }

    // -------------------------- Term --------------------------

    @PreAuthorize("@perm.can('structure.view')")
    @GetMapping("/academic-years/{academicYearId}/terms")
    public List<TermDto> terms(@PathVariable UUID academicYearId) {
        return repo.listTerms(academicYearId);
    }

    public record CreateTermRequest(
        @NotBlank String code, @NotBlank String name, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn
    ) {}

    @PreAuthorize("@perm.can('structure.manage')")
    @PostMapping("/academic-years/{academicYearId}/terms")
    public TermDto createTerm(@PathVariable UUID academicYearId, @RequestBody CreateTermRequest req) {
        return repo.createTerm(academicYearId, req.code(), req.name(), req.startsOn(), req.endsOn());
    }

    // -------------------------- Grade --------------------------

    public record CreateGradeRequest(@NotBlank String code, @NotBlank String name, int sortOrder) {}

    @PreAuthorize("@perm.can('structure.manage')")
    @PostMapping("/schools/{schoolId}/grades")
    public GradeDto createGrade(@PathVariable UUID schoolId, @RequestBody CreateGradeRequest req) {
        return repo.createGrade(schoolId, req.code(), req.name(), req.sortOrder());
    }

    // -------------------------- Section --------------------------

    public record CreateSectionRequest(
        @NotNull UUID gradeId, @NotNull UUID academicYearId, @NotBlank String code, @NotBlank String name,
        @NotBlank String strategyCode, Integer capacity, UUID campusId
    ) {}

    @PreAuthorize("@perm.can('structure.manage')")
    @PostMapping("/schools/{schoolId}/sections")
    public SectionDto createSection(@PathVariable UUID schoolId, @RequestBody CreateSectionRequest req) {
        return repo.createSection(
            schoolId, req.gradeId(), req.academicYearId(), req.code(), req.name(), req.strategyCode(),
            req.capacity(), req.campusId()
        );
    }

    public record BindCurriculumRequest(@NotNull UUID curriculumId, @NotBlank String strategyCode) {}

    @PreAuthorize("@perm.can('curriculum.manage')")
    @PutMapping("/sections/{sectionId}/curriculum")
    public ResponseEntity<Void> bindCurriculum(@PathVariable UUID sectionId, @RequestBody BindCurriculumRequest req) {
        repo.bindSectionCurriculum(sectionId, req.curriculumId(), req.strategyCode());
        return ResponseEntity.noContent().build();
    }

    // -------------------------- Subject --------------------------

    @PreAuthorize("@perm.can('structure.view')")
    @GetMapping("/schools/{schoolId}/subjects")
    public List<SubjectDto> subjects(@PathVariable UUID schoolId) {
        return repo.listSubjects(schoolId);
    }

    public record CreateSubjectRequest(@NotBlank String code, @NotBlank String name, String boardCode) {}

    @PreAuthorize("@perm.can('structure.manage')")
    @PostMapping("/schools/{schoolId}/subjects")
    public SubjectDto createSubject(@PathVariable UUID schoolId, @RequestBody CreateSubjectRequest req) {
        return repo.createSubject(schoolId, req.code(), req.name(), req.boardCode());
    }

    // -------------------------- Section-Subject-Teacher --------------------------

    @PreAuthorize("@perm.can('structure.view')")
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

    @PreAuthorize("@perm.can('teacher.assign')")
    @PostMapping("/sections/{sectionId}/teachers")
    public SectionSubjectTeacherDto assignTeacher(@PathVariable UUID sectionId, @RequestBody AssignTeacherRequest req) {
        return repo.assignSectionSubjectTeacher(
            sectionId, req.subjectId(), req.teacherStaffId(), req.isPrimary(), req.isElective());
    }

    // -------------------------- Elective groups --------------------------

    @PreAuthorize("@perm.can('structure.view')")
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

    @PreAuthorize("@perm.can('structure.manage')")
    @PostMapping("/schools/{schoolId}/elective-groups")
    public ElectiveGroupDto createElectiveGroup(
        @PathVariable UUID schoolId, @RequestBody CreateElectiveGroupRequest req
    ) {
        return repo.createElectiveGroup(schoolId, req.academicYearId(), req.gradeId(), req.code(), req.name(),
            req.minPicks() == 0 ? 1 : req.minPicks(), req.maxPicks() == 0 ? 1 : req.maxPicks(), req.subjectIds());
    }
}
