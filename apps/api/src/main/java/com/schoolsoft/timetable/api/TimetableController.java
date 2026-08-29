package com.schoolsoft.timetable.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.iam.api.SelfScope;
import com.schoolsoft.platform.security.Perm;
import com.schoolsoft.timetable.internal.BellScheduleRepository;
import com.schoolsoft.timetable.internal.CoverRepository;
import com.schoolsoft.timetable.internal.TimetableRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/timetable")
public class TimetableController {

    private final TimetableRepository repo;
    private final BellScheduleRepository bells;
    private final CoverRepository covers;
    private final SelfScope selfScope;

    public TimetableController(TimetableRepository repo, BellScheduleRepository bells,
                               CoverRepository covers, SelfScope selfScope) {
        this.repo = repo;
        this.bells = bells;
        this.covers = covers;
        this.selfScope = selfScope;
    }

    @PreAuthorize("@perm.canAnyOf('timetable.view', 'timetable.view.own')")
    @GetMapping("/sections/{sectionId}")
    public List<TimetableSlotDto> forSection(@PathVariable UUID sectionId) {
        selfScope.requireSection(sectionId, Perm.TIMETABLE_VIEW);
        return repo.forSection(sectionId);
    }

    /** One date's periods, or the reason the school is closed that day (CAL-03). */
    @PreAuthorize("@perm.canAnyOf('timetable.view', 'timetable.view.own')")
    @GetMapping("/sections/{sectionId}/day")
    public SectionDayDto forSectionOnDate(
        @PathVariable UUID sectionId,
        @RequestParam @org.springframework.format.annotation.DateTimeFormat(
            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        selfScope.requireSection(sectionId, Perm.TIMETABLE_VIEW);
        return repo.forSectionOnDate(sectionId, date);
    }

    /** A student's own periods — the section's week filtered to their subject set. */
    @PreAuthorize("@perm.canAnyOf('timetable.view', 'timetable.view.own')")
    @GetMapping("/students/{studentId}")
    public List<TimetableSlotDto> forStudent(
        @PathVariable UUID studentId,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate onDate
    ) {
        selfScope.requireStudent(studentId, Perm.TIMETABLE_VIEW);
        return repo.forStudent(studentId, onDate);
    }

    @PreAuthorize("@perm.can('timetable.view')")
    @GetMapping("/teachers/{teacherStaffId}")
    public List<TimetableSlotDto> forTeacher(@PathVariable UUID teacherStaffId) {
        return repo.forTeacher(teacherStaffId);
    }

    /** A teacher's day including cover taken and cover given away (TT-08). */
    @PreAuthorize("@perm.can('timetable.view')")
    @GetMapping("/teachers/{teacherStaffId}/day")
    public TeacherDayDto forTeacherOnDate(
        @PathVariable UUID teacherStaffId,
        @RequestParam @org.springframework.format.annotation.DateTimeFormat(
            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return repo.forTeacherOnDate(teacherStaffId, date);
    }

    // -------------------------- Cover (GAP-07) --------------------------

    /**
     * The day's periods whose teacher is on approved leave, each with the
     * people free to take it (STF-03).
     */
    @PreAuthorize("@perm.can('cover.view')")
    @GetMapping("/cover/needs")
    public List<CoverNeedDto> coverNeeds(
        @RequestParam UUID schoolId,
        @RequestParam @org.springframework.format.annotation.DateTimeFormat(
            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return covers.needs(schoolId, date);
    }

    @PreAuthorize("@perm.can('cover.view')")
    @GetMapping("/cover")
    public List<TimetableCoverDto> coverForDay(
        @RequestParam UUID schoolId,
        @RequestParam @org.springframework.format.annotation.DateTimeFormat(
            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return covers.forSchool(schoolId, date);
    }

    public record AssignCoverRequest(
        @NotNull UUID slotId, @NotNull LocalDate onDate, @NotNull UUID substituteStaffId, String reason
    ) {}

    @PreAuthorize("@perm.can('cover.manage')")
    @PostMapping("/cover")
    public TimetableCoverDto assignCover(@RequestBody AssignCoverRequest req) {
        return covers.assign(req.slotId(), req.onDate(), req.substituteStaffId(), req.reason());
    }

    @PreAuthorize("@perm.can('cover.manage')")
    @DeleteMapping("/cover/{id}")
    public TimetableCoverDto cancelCover(@PathVariable UUID id) {
        return covers.cancel(id);
    }

    /**
     * Give either {@code periodId} (times come from the bell schedule) or
     * explicit {@code startsAt}/{@code endsAt}.
     */
    public record CreateSlotRequest(
        @NotNull UUID sectionId, @NotNull UUID subjectId, @NotNull UUID teacherStaffId,
        int dayOfWeek, int periodNo, LocalTime startsAt, LocalTime endsAt,
        String room, @NotNull LocalDate effectiveFrom, LocalDate effectiveTo, UUID periodId
    ) {}

    @PreAuthorize("@perm.can('timetable.manage')")
    @PostMapping("/slots")
    public TimetableSlotDto createSlot(@RequestBody CreateSlotRequest req) {
        return repo.createSlot(
            req.sectionId(), req.subjectId(), req.teacherStaffId(), req.dayOfWeek(), req.periodNo(),
            req.startsAt(), req.endsAt(), req.room(), req.effectiveFrom(), req.effectiveTo(), req.periodId()
        );
    }

    // -------------------------- Bell schedules (GAP-12) --------------------------

    @PreAuthorize("@perm.can('timetable.view')")
    @GetMapping("/bell-schedules")
    public List<BellScheduleDto> bellSchedules(@RequestParam UUID schoolId) {
        return bells.list(schoolId);
    }

    @PreAuthorize("@perm.can('timetable.view')")
    @GetMapping("/sections/{sectionId}/bell-schedule")
    public BellScheduleDto bellScheduleForSection(@PathVariable UUID sectionId) {
        return bells.forSection(sectionId);
    }

    public record PeriodRequest(
        int periodNo, @NotBlank String label, @NotNull LocalTime startsAt, @NotNull LocalTime endsAt,
        boolean isBreak
    ) {}

    public record CreateBellScheduleRequest(
        @NotNull UUID schoolId, UUID campusId, @NotBlank String code, @NotBlank String name,
        @NotNull LocalDate effectiveFrom, LocalDate effectiveTo,
        @NotNull List<PeriodRequest> periods, List<UUID> gradeIds
    ) {}

    @PreAuthorize("@perm.can('timetable.manage')")
    @PostMapping("/bell-schedules")
    public BellScheduleDto createBellSchedule(@RequestBody CreateBellScheduleRequest req) {
        return bells.create(req.schoolId(), req.campusId(), req.code(), req.name(),
            req.effectiveFrom(), req.effectiveTo(),
            req.periods().stream()
                .map(p -> new BellScheduleRepository.PeriodInput(
                    p.periodNo(), p.label(), p.startsAt(), p.endsAt(), p.isBreak()))
                .toList(),
            req.gradeIds());
    }

    /** Warnings a section's timetable carries at publish time (TT-04). */
    @PreAuthorize("@perm.can('timetable.manage')")
    @GetMapping("/sections/{sectionId}/publish-warnings")
    public Map<String, Object> publishWarnings(@PathVariable UUID sectionId) {
        List<String> warnings = repo.publishWarnings(sectionId);
        return Map.of("sectionId", sectionId, "warnings", warnings, "publishable", true);
    }

    @PreAuthorize("@perm.can('timetable.manage')")
    @DeleteMapping("/slots/{id}")
    public ResponseEntity<Void> deleteSlot(@PathVariable UUID id) {
        repo.deleteSlot(id);
        return ResponseEntity.noContent().build();
    }
}
