package com.schoolsoft.admissions.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.admissions.internal.AdmissionsRepository;
import com.schoolsoft.admissions.internal.AdmissionsService;
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
    private final AdmissionsService applications;

    public AdmissionsController(AdmissionsRepository repo, AdmissionsService applications) {
        this.repo = repo;
        this.applications = applications;
    }

    /**
     * One page of one stage. {@code limit} is optional so an export can still
     * ask for everything; the pipeline screen always names one, because the
     * page opens on counts and fetches rows only for the stage somebody picked.
     */
    @PreAuthorize("@perm.can('admission.view')")
    @GetMapping("/applications")
    public List<AdmissionApplicationDto> list(
        @RequestParam UUID schoolId,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) Integer limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        return repo.list(schoolId, state, limit, offset);
    }

    /**
     * The funnel as counts — what the pipeline screen opens with, instead of
     * every application in the school.
     */
    @PreAuthorize("@perm.can('admission.view')")
    @GetMapping("/summary")
    public AdmissionFunnelSummaryDto summary(@RequestParam UUID schoolId) {
        return repo.summary(schoolId, AdmissionsRepository.STATES);
    }

    /**
     * Find one child. The office takes a phone call and has a name, sometimes a
     * date of birth, sometimes the number off the acknowledgement — so {@code q}
     * searches the few things a family quotes back, and the named fields narrow
     * it when the name alone brings back six children.
     *
     * <p>A search with no criteria is refused rather than answered: it is a
     * request for the whole table wearing a question's clothes, and the stage
     * tiles are the way to browse.</p>
     */
    @PreAuthorize("@perm.can('admission.view')")
    @GetMapping("/applications/search")
    public AdmissionSearchResultDto search(
        @RequestParam UUID schoolId,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String name,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso =
            org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dob,
        @RequestParam(required = false) String guardianPhone,
        @RequestParam(required = false) String applicationNo,
        @RequestParam(required = false) UUID gradeId,
        @RequestParam(required = false) UUID academicYearId,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String source,
        @RequestParam(defaultValue = "25") Integer limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        var criteria = new AdmissionsRepository.SearchCriteria(
            schoolId, q, name, dob, guardianPhone, applicationNo, gradeId, academicYearId,
            state, source, limit, offset);
        if (criteria.isEmpty()) {
            throw new IllegalArgumentException(
                "Give the search something to go on \u2014 a name, a date of birth, an application number "
                + "or a phone. Browse by stage instead to see everyone.");
        }
        return repo.search(criteria);
    }

    /**
     * Every stage's legal moves at this school, in one answer. Search results
     * hold applications in many states at once, so the screen takes the whole
     * map rather than a request per row.
     */
    @PreAuthorize("@perm.can('admission.view')")
    @GetMapping("/moves/all")
    public Map<String, List<String>> allMoves(@RequestParam UUID schoolId) {
        return repo.allMoves(applications.policy(schoolId).entranceTestRequired());
    }

    /**
     * The moves a stage may make at this school, without naming an application.
     * Every row in a stage shares them, so the screen asks once rather than
     * once per row.
     */
    @PreAuthorize("@perm.can('admission.view')")
    @GetMapping("/moves")
    public List<String> movesForState(@RequestParam UUID schoolId, @RequestParam String fromState) {
        return repo.movesFrom(fromState, applications.policy(schoolId).entranceTestRequired());
    }

    @PreAuthorize("@perm.can('admission.view')")
    @GetMapping("/applications/{id}")
    public ResponseEntity<AdmissionApplicationDto> get(@PathVariable UUID id) {
        return repo.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /**
     * {@code applicationNo} is optional and normally left out: the school's
     * number series issues it, like every other number in the product. Passing
     * one explicitly is the exception the convention allows — a back-office
     * import carrying numbers a family already holds.
     */
    public record CreateApplicationRequest(
        @NotNull UUID schoolId, @NotNull UUID academicYearId, @NotNull UUID gradeId, String applicationNo,
        @NotBlank String applicantFirstName, String applicantLastName, LocalDate applicantDob, String applicantGender,
        @NotBlank String guardianName, @NotBlank String guardianPhone, String guardianEmail, String source
    ) {}

    @PreAuthorize("@perm.can('admission.manage')")
    @PostMapping("/applications")
    public AdmissionApplicationDto create(@RequestBody CreateApplicationRequest req) {
        return applications.create(
            req.schoolId(), req.academicYearId(), req.gradeId(), req.applicationNo(),
            req.applicantFirstName(), req.applicantLastName(), req.applicantDob(), req.applicantGender(),
            req.guardianName(), req.guardianPhone(), req.guardianEmail(), req.source()
        );
    }

    /**
     * {@code offerExpiresOn} is only read on a move to {@code offered}, and only
     * to override the school's own offer window — which is how the office
     * extends a deadline for one family.
     */
    public record TransitionRequest(@NotBlank String toState, LocalDate offerExpiresOn) {}

    @PreAuthorize("@perm.can('admission.decide')")
    @PostMapping("/applications/{id}/transition")
    public AdmissionApplicationDto transition(@PathVariable UUID id, @RequestBody TransitionRequest req) {
        var snap = TenantContext.get();
        UUID actor = snap == null ? null : snap.userAccountId();
        return applications.transition(id, req.toState(), actor, req.offerExpiresOn());
    }

    public record TestScoreRequest(double score, String notes) {}

    @PreAuthorize("@perm.can('admission.manage')")
    @PostMapping("/applications/{id}/test-score")
    public AdmissionApplicationDto testScore(@PathVariable UUID id, @RequestBody TestScoreRequest req) {
        var snap = TenantContext.get();
        return applications.recordTestScore(id, req.score(), req.notes(),
            snap == null ? null : snap.userAccountId());
    }

    // --------------------------------------------------------------- policy

    public record PolicyRequest(@NotNull UUID schoolId, boolean entranceTestRequired, int offerValidityDays) {}

    /**
     * Which funnel this school runs. Read with the funnel itself, because the
     * board needs it to know which lanes exist.
     */
    @PreAuthorize("@perm.can('admission.view')")
    @GetMapping("/policy")
    public AdmissionPolicyDto policy(@RequestParam UUID schoolId) {
        return applications.policy(schoolId);
    }

    /**
     * Configuring the funnel is a setup action, not a step in working it, so it
     * answers to its own permission: a counsellor who moves applications all day
     * does not get to decide whether the school holds an entrance test.
     */
    @PreAuthorize("@perm.can('admission.policy.manage')")
    @PutMapping("/policy")
    public AdmissionPolicyDto savePolicy(@RequestBody PolicyRequest req) {
        return applications.savePolicy(req.schoolId(), req.entranceTestRequired(), req.offerValidityDays());
    }

    /**
     * The moves this application may make from where it stands. The board reads
     * it so a lane it cannot drop into is disabled rather than refused after
     * the drop — the server stays authoritative either way.
     */
    @PreAuthorize("@perm.can('admission.view')")
    @GetMapping("/applications/{id}/moves")
    public List<String> moves(@PathVariable UUID id) {
        var application = repo.find(id).orElseThrow(
            () -> new com.schoolsoft.platform.web.NotFoundException("Application not found: " + id));
        return repo.movesFrom(application.state(),
            applications.policy(application.schoolId()).entranceTestRequired());
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
        UUID studentId = applications.enrol(id, req.sectionId(), req.rollNo(), req.overCapacityReason());
        return Map.of("studentId", studentId);
    }
}
