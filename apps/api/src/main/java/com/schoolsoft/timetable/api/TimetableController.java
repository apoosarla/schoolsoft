package com.schoolsoft.timetable.api;

import com.schoolsoft.timetable.internal.BellScheduleRepository;
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

    public TimetableController(TimetableRepository repo, BellScheduleRepository bells) {
        this.repo = repo;
        this.bells = bells;
    }

    @GetMapping("/sections/{sectionId}")
    public List<TimetableSlotDto> forSection(@PathVariable UUID sectionId) {
        return repo.forSection(sectionId);
    }

    /** One date's periods, or the reason the school is closed that day (CAL-03). */
    @GetMapping("/sections/{sectionId}/day")
    public SectionDayDto forSectionOnDate(
        @PathVariable UUID sectionId,
        @RequestParam @org.springframework.format.annotation.DateTimeFormat(
            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return repo.forSectionOnDate(sectionId, date);
    }

    /** A student's own periods — the section's week filtered to their subject set. */
    @GetMapping("/students/{studentId}")
    public List<TimetableSlotDto> forStudent(
        @PathVariable UUID studentId,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate onDate
    ) {
        return repo.forStudent(studentId, onDate);
    }

    @GetMapping("/teachers/{teacherStaffId}")
    public List<TimetableSlotDto> forTeacher(@PathVariable UUID teacherStaffId) {
        return repo.forTeacher(teacherStaffId);
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

    @PostMapping("/slots")
    public TimetableSlotDto createSlot(@RequestBody CreateSlotRequest req) {
        return repo.createSlot(
            req.sectionId(), req.subjectId(), req.teacherStaffId(), req.dayOfWeek(), req.periodNo(),
            req.startsAt(), req.endsAt(), req.room(), req.effectiveFrom(), req.effectiveTo(), req.periodId()
        );
    }

    // -------------------------- Bell schedules (GAP-12) --------------------------

    @GetMapping("/bell-schedules")
    public List<BellScheduleDto> bellSchedules(@RequestParam UUID schoolId) {
        return bells.list(schoolId);
    }

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
    @GetMapping("/sections/{sectionId}/publish-warnings")
    public Map<String, Object> publishWarnings(@PathVariable UUID sectionId) {
        List<String> warnings = repo.publishWarnings(sectionId);
        return Map.of("sectionId", sectionId, "warnings", warnings, "publishable", true);
    }

    @DeleteMapping("/slots/{id}")
    public ResponseEntity<Void> deleteSlot(@PathVariable UUID id) {
        repo.deleteSlot(id);
        return ResponseEntity.noContent().build();
    }
}
