package com.schoolsoft.schoolcalendar.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.audit.api.AuditService;
import com.schoolsoft.notification.api.NotificationService;
import com.schoolsoft.schoolcalendar.internal.CalendarRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Calendar authoring and reading (GAP-01).
 *
 * The read side ({@code /days}) is what every client should ask rather than
 * deriving school days from a weekday check — it already accounts for the
 * working-day pattern, holidays, vacation blocks, working Saturdays, and
 * grade- or campus-scoped entries.
 */
@RestController
@RequestMapping("/v1/calendar")
public class CalendarController {

    private final CalendarRepository repo;
    private final WorkingDayService workingDays;
    private final NotificationService notifications;
    private final AuditService audit;

    public CalendarController(CalendarRepository repo, WorkingDayService workingDays,
                              NotificationService notifications, AuditService audit) {
        this.repo = repo;
        this.workingDays = workingDays;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ------------------------------------------------------- working patterns

    @PreAuthorize("@perm.can('calendar.view')")
    @GetMapping("/patterns")
    public List<WorkingDayPatternDto> patterns(@RequestParam UUID schoolId) {
        return repo.listPatterns(schoolId);
    }

    public record CreatePatternRequest(
        @NotNull UUID schoolId, UUID campusId, @NotNull LocalDate effectiveFrom, LocalDate effectiveTo,
        @NotBlank String weekdayMask, @NotBlank String saturdayRule, String notes
    ) {}

    @PreAuthorize("@perm.can('calendar.manage')")
    @PostMapping("/patterns")
    public WorkingDayPatternDto createPattern(@RequestBody CreatePatternRequest req) {
        return repo.upsertPattern(req.schoolId(), req.campusId(), req.effectiveFrom(), req.effectiveTo(),
            req.weekdayMask(), req.saturdayRule(), req.notes());
    }

    // -------------------------------------------------------- calendar entries

    @PreAuthorize("@perm.can('calendar.view')")
    @GetMapping("/entries")
    public List<CalendarEntryDto> entries(
        @RequestParam UUID schoolId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return repo.listEntries(schoolId, from, to);
    }

    public record CreateEntryRequest(
        @NotNull UUID schoolId, UUID academicYearId, @NotNull LocalDate onDate, @NotBlank String kind,
        @NotBlank String title, String description, UUID gradeId, UUID campusId, UUID declaredByStaffId
    ) {}

    @PreAuthorize("@perm.can('calendar.manage')")
    @PostMapping("/entries")
    public CalendarEntryDto createEntry(@RequestBody CreateEntryRequest req) {
        return repo.upsertEntry(req.schoolId(), req.academicYearId(), req.onDate(), req.kind(),
            req.title(), req.description(), req.gradeId(), req.campusId(), "manual", req.declaredByStaffId());
    }

    public record BulkImportRequest(
        @NotNull UUID schoolId, UUID academicYearId, @NotNull List<CreateEntryRequest> entries
    ) {}

    /**
     * Gazetted-holiday import. Idempotent per entry, so re-running last year's
     * list against this year's dates, or re-importing after a partial failure,
     * corrects rather than duplicates.
     */
    @PreAuthorize("@perm.can('calendar.manage')")
    @PostMapping("/entries/bulk")
    public List<CalendarEntryDto> importEntries(@RequestBody BulkImportRequest req) {
        return req.entries().stream()
            .map(entry -> repo.upsertEntry(
                req.schoolId(),
                entry.academicYearId() == null ? req.academicYearId() : entry.academicYearId(),
                entry.onDate(), entry.kind(), entry.title(), entry.description(),
                entry.gradeId(), entry.campusId(), "gazetted_import", entry.declaredByStaffId()))
            .toList();
    }

    @PreAuthorize("@perm.can('calendar.manage')")
    @DeleteMapping("/entries/{id}")
    public ResponseEntity<Void> deleteEntry(@PathVariable UUID id) {
        repo.deleteEntry(id);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------- day resolution

    public record DayStatusDto(LocalDate date, boolean working, String reason, String calendarKind) {}

    @PreAuthorize("@perm.can('calendar.view')")
    @GetMapping("/days")
    public List<DayStatusDto> days(
        @RequestParam UUID schoolId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) UUID gradeId,
        @RequestParam(required = false) UUID campusId
    ) {
        return workingDays.calendar(schoolId, from, to, gradeId, campusId).stream()
            .map(status -> new DayStatusDto(status.date(), status.working(), status.reason(), status.calendarKind()))
            .toList();
    }

    @PreAuthorize("@perm.can('calendar.view')")
    @GetMapping("/working-days")
    public Map<String, Object> workingDayCount(
        @RequestParam UUID schoolId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) UUID gradeId,
        @RequestParam(required = false) UUID campusId
    ) {
        List<LocalDate> days = workingDays.workingDays(schoolId, from, to, gradeId, campusId);
        return Map.of("from", from, "to", to, "workingDays", days.size(), "dates", days);
    }

    // ----------------------------------------------------------------- closures

    public record DeclareClosureRequest(
        @NotNull UUID schoolId, @NotNull LocalDate onDate, @NotBlank String title, String description,
        UUID gradeId, UUID campusId, UUID declaredByStaffId
    ) {}

    public record ClosureResultDto(
        CalendarEntryDto entry, int voidedAttendanceRecords, int guardiansNotified
    ) {}

    /**
     * Declares an unplanned closure (weather, strike). Three things follow, and
     * they follow together on purpose: the day stops counting as a working day,
     * attendance already marked for it is voided — retained, not deleted, so the
     * school can show what was taken and that it was withdrawn — and the
     * affected guardians are told.
     */
    @PreAuthorize("@perm.can('closure.declare')")
    @PostMapping("/closures")
    public ClosureResultDto declareClosure(@RequestBody DeclareClosureRequest req) {
        CalendarEntryDto entry = repo.upsertEntry(req.schoolId(), null, req.onDate(), "closure",
            req.title(), req.description(), req.gradeId(), req.campusId(), "manual", req.declaredByStaffId());

        List<UUID> affectedStudents = repo.voidAttendanceFor(req.schoolId(), req.onDate(),
            req.gradeId(), req.campusId(), req.title(), req.declaredByStaffId());

        List<UUID> guardians = repo.guardianIdsForStudents(affectedStudents);
        for (UUID guardianId : guardians) {
            notifications.notify(req.schoolId(), "guardian", guardianId, "school_closure",
                Map.of("date", req.onDate().toString(), "reason", req.title()));
        }

        audit.record("calendar.closure_declared", "school_calendar", entry.id(), null,
            Map.of("onDate", req.onDate().toString(), "title", req.title(),
                   "voidedAttendance", affectedStudents.size(),
                   "guardiansNotified", guardians.size()));

        return new ClosureResultDto(entry, affectedStudents.size(), guardians.size());
    }
}
