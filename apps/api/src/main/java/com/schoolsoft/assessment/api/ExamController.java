package com.schoolsoft.assessment.api;

import com.schoolsoft.assessment.internal.ExamScheduleRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/** Exam weeks: papers, rooms, invigilators, clashes and hall tickets (ASMT-09). */
@RestController
@RequestMapping("/v1/exams")
public class ExamController {

    private final ExamScheduleRepository repo;

    public ExamController(ExamScheduleRepository repo) { this.repo = repo; }

    @GetMapping("/schedules")
    public List<ExamScheduleDto> schedules(@RequestParam UUID schoolId,
                                           @RequestParam(required = false) UUID academicYearId) {
        return repo.schedules(schoolId, academicYearId);
    }

    @GetMapping("/schedules/{id}")
    public ExamScheduleDto schedule(@PathVariable UUID id) {
        return repo.schedule(id);
    }

    public record CreateScheduleRequest(
        @NotNull UUID schoolId, @NotNull UUID academicYearId, UUID termId,
        @NotBlank String code, @NotBlank String name,
        @NotNull LocalDate startsOn, @NotNull LocalDate endsOn
    ) {}

    @PostMapping("/schedules")
    public ExamScheduleDto createSchedule(@RequestBody CreateScheduleRequest req) {
        return repo.createSchedule(req.schoolId(), req.academicYearId(), req.termId(), req.code(), req.name(),
            req.startsOn(), req.endsOn());
    }

    public record CreateSessionRequest(
        @NotNull UUID gradeId, @NotNull UUID subjectId, String paperCode, @NotBlank String name,
        @NotNull LocalDate onDate, @NotNull LocalTime startsAt, @NotNull LocalTime endsAt,
        String room, UUID invigilatorStaffId, Double maxMarks, UUID assessmentId
    ) {}

    @PostMapping("/schedules/{id}/sessions")
    public ExamSessionDto addSession(@PathVariable UUID id, @RequestBody CreateSessionRequest req) {
        return repo.addSession(id, req.gradeId(), req.subjectId(), req.paperCode(), req.name(), req.onDate(),
            req.startsAt(), req.endsAt(), req.room(), req.invigilatorStaffId(), req.maxMarks(), req.assessmentId());
    }

    @GetMapping("/schedules/{id}/sessions")
    public List<ExamSessionDto> sessions(@PathVariable UUID id) {
        return repo.sessions(id);
    }

    /**
     * Per-student clashes. A section timetable cannot see these: with option
     * blocks the grade's papers can be clash-free while one candidate is booked
     * into two rooms at once.
     */
    @GetMapping("/schedules/{id}/clashes")
    public Map<String, Object> clashes(@PathVariable UUID id) {
        var clashes = repo.clashes(id);
        return Map.of("examScheduleId", id, "clashCount", clashes.size(), "clashes", clashes);
    }

    /** Refuses while a clash stands — from here on, the schedule is what the school runs. */
    @PostMapping("/schedules/{id}/publish")
    public ExamScheduleDto publish(@PathVariable UUID id) {
        return repo.publish(id);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public org.springframework.http.ResponseEntity<Void> deleteSession(@PathVariable UUID sessionId) {
        repo.deleteSession(sessionId);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @PostMapping("/schedules/{id}/unpublish")
    public ExamScheduleDto unpublish(@PathVariable UUID id) {
        return repo.unpublish(id);
    }

    @PostMapping("/schedules/{id}/hall-tickets")
    public List<HallTicketDto> issueHallTickets(@PathVariable UUID id) {
        return repo.issueHallTickets(id);
    }

    @GetMapping("/schedules/{id}/hall-tickets")
    public List<HallTicketDto> hallTickets(@PathVariable UUID id) {
        return repo.hallTickets(id);
    }

    @GetMapping("/schedules/{id}/hall-tickets/{studentId}")
    public HallTicketDto hallTicket(@PathVariable UUID id, @PathVariable UUID studentId) {
        return repo.hallTicket(id, studentId);
    }

    /** The papers one student sits, out of the whole schedule. */
    @GetMapping("/schedules/{id}/students/{studentId}/sessions")
    public List<ExamSessionDto> sessionsForStudent(@PathVariable UUID id, @PathVariable UUID studentId) {
        return repo.sessionsForStudent(id, studentId);
    }

    /** What a grade sits on one date — the list that replaces the class timetable (TT-09). */
    @GetMapping("/sessions")
    public List<ExamSessionDto> sessionsOn(
        @RequestParam UUID gradeId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return repo.publishedSessionsForGradeOn(gradeId, date);
    }
}
