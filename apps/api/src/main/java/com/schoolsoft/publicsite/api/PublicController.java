package com.schoolsoft.publicsite.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.admissions.api.AdmissionApplicationDto;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.publicsite.internal.PublicLookupRepository;
import com.schoolsoft.tenancy.api.GradeDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Reachable without a JWT (whitelisted under {@code /v1/public/**} in
 * {@code SecurityConfig}). Backs {@code apps/public-site}: school info and an
 * admissions inquiry form anyone can submit.
 */
@RestController
@RequestMapping("/v1/public/schools/{chainSlug}/{schoolSlug}")
public class PublicController {

    private final PublicLookupRepository repo;
    public PublicController(PublicLookupRepository repo) { this.repo = repo; }

    @PreAuthorize("permitAll()")
    @GetMapping
    public PublicSchoolDto school(@PathVariable String chainSlug, @PathVariable String schoolSlug) {
        return repo.findSchool(chainSlug, schoolSlug)
            .orElseThrow(() -> new NotFoundException("No school for " + chainSlug + "/" + schoolSlug));
    }

    @PreAuthorize("permitAll()")
    @GetMapping("/grades")
    public List<GradeDto> grades(@PathVariable String chainSlug, @PathVariable String schoolSlug) {
        return repo.listGrades(chainSlug, schoolSlug);
    }

    /** Published school calendar — the same day resolution the apps see (CAL-07). */
    @PreAuthorize("permitAll()")
    @GetMapping("/calendar")
    public List<PublicCalendarDayDto> calendar(
        @PathVariable String chainSlug, @PathVariable String schoolSlug,
        @RequestParam @org.springframework.format.annotation.DateTimeFormat(
            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @org.springframework.format.annotation.DateTimeFormat(
            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return repo.calendar(chainSlug, schoolSlug, from, to).stream()
            .map(day -> new PublicCalendarDayDto(
                day.date(), day.working(), day.reason(), day.calendarKind()))
            .toList();
    }

    public record PublicCalendarDayDto(LocalDate date, boolean working, String reason, String calendarKind) {}

    public record ApplyRequest(
        @NotBlank String applicantFirstName, String applicantLastName, LocalDate applicantDob, String applicantGender,
        @NotNull UUID gradeId, @NotBlank String guardianName, @NotBlank String guardianPhone, String guardianEmail
    ) {}

    @PreAuthorize("permitAll()")
    @PostMapping("/admissions/apply")
    public Map<String, String> apply(
        @PathVariable String chainSlug, @PathVariable String schoolSlug, @RequestBody ApplyRequest req
    ) {
        AdmissionApplicationDto created = repo.apply(chainSlug, schoolSlug, new PublicLookupRepository.ApplyRequest(
            req.applicantFirstName(), req.applicantLastName(), req.applicantDob(), req.applicantGender(),
            req.gradeId(), req.guardianName(), req.guardianPhone(), req.guardianEmail()
        ));
        return Map.of("applicationNo", created.applicationNo());
    }

    @PreAuthorize("permitAll()")
    @GetMapping("/admissions/track")
    public AdmissionApplicationDto track(
        @PathVariable String chainSlug, @PathVariable String schoolSlug,
        @RequestParam String applicationNo, @RequestParam String guardianPhone
    ) {
        return repo.track(chainSlug, applicationNo, guardianPhone)
            .orElseThrow(() -> new NotFoundException("No application found for that number and phone"));
    }
}
