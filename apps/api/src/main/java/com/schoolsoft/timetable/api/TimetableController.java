package com.schoolsoft.timetable.api;

import com.schoolsoft.timetable.internal.TimetableRepository;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/timetable")
public class TimetableController {

    private final TimetableRepository repo;
    public TimetableController(TimetableRepository repo) { this.repo = repo; }

    @GetMapping("/sections/{sectionId}")
    public List<TimetableSlotDto> forSection(@PathVariable UUID sectionId) {
        return repo.forSection(sectionId);
    }

    @GetMapping("/teachers/{teacherStaffId}")
    public List<TimetableSlotDto> forTeacher(@PathVariable UUID teacherStaffId) {
        return repo.forTeacher(teacherStaffId);
    }

    public record CreateSlotRequest(
        @NotNull UUID sectionId, @NotNull UUID subjectId, @NotNull UUID teacherStaffId,
        int dayOfWeek, int periodNo, @NotNull LocalTime startsAt, @NotNull LocalTime endsAt,
        String room, @NotNull LocalDate effectiveFrom, LocalDate effectiveTo
    ) {}

    @PostMapping("/slots")
    public TimetableSlotDto createSlot(@RequestBody CreateSlotRequest req) {
        return repo.createSlot(
            req.sectionId(), req.subjectId(), req.teacherStaffId(), req.dayOfWeek(), req.periodNo(),
            req.startsAt(), req.endsAt(), req.room(), req.effectiveFrom(), req.effectiveTo()
        );
    }

    @DeleteMapping("/slots/{id}")
    public ResponseEntity<Void> deleteSlot(@PathVariable UUID id) {
        repo.deleteSlot(id);
        return ResponseEntity.noContent().build();
    }
}
