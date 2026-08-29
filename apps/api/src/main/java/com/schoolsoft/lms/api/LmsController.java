package com.schoolsoft.lms.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.lms.internal.LmsRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/lms")
public class LmsController {

    private final LmsRepository repo;
    public LmsController(LmsRepository repo) { this.repo = repo; }

    // -------------------------- Content --------------------------

    @PreAuthorize("@perm.can('lms.content.view')")
    @GetMapping("/content")
    public List<ContentItemDto> content(@RequestParam UUID schoolId, @RequestParam(required = false) UUID subjectId) {
        return repo.listContent(schoolId, subjectId);
    }

    public record CreateContentRequest(
        @NotNull UUID schoolId, UUID subjectId, UUID curriculumNodeId, @NotBlank String title,
        Map<String, Object> body, String visibility, UUID createdByStaffId
    ) {}

    @PreAuthorize("@perm.can('lms.content.manage')")
    @PostMapping("/content")
    public ContentItemDto createContent(@RequestBody CreateContentRequest req) {
        return repo.createContent(
            req.schoolId(), req.subjectId(), req.curriculumNodeId(), req.title(), req.body(), req.visibility(), req.createdByStaffId()
        );
    }

    // -------------------------- Lesson Plans --------------------------

    @PreAuthorize("@perm.can('lms.content.manage')")
    @GetMapping("/lesson-plans")
    public List<LessonPlanDto> lessonPlans(@RequestParam UUID sectionId) {
        return repo.listLessonPlans(sectionId);
    }

    public record CreateLessonPlanRequest(
        @NotNull UUID schoolId, @NotNull UUID sectionId, @NotNull UUID subjectId, UUID curriculumNodeId,
        @NotBlank String title, LocalDate plannedFor, Integer durationMinutes, UUID createdByStaffId
    ) {}

    @PreAuthorize("@perm.can('lms.content.manage')")
    @PostMapping("/lesson-plans")
    public LessonPlanDto createLessonPlan(@RequestBody CreateLessonPlanRequest req) {
        return repo.createLessonPlan(
            req.schoolId(), req.sectionId(), req.subjectId(), req.curriculumNodeId(), req.title(),
            req.plannedFor(), req.durationMinutes(), req.createdByStaffId()
        );
    }

    public record StatusRequest(@NotBlank String status) {}

    @PreAuthorize("@perm.can('lms.content.manage')")
    @PostMapping("/lesson-plans/{id}/status")
    public LessonPlanDto setLessonPlanStatus(@PathVariable UUID id, @RequestBody StatusRequest req) {
        return repo.setLessonPlanStatus(id, req.status());
    }

    // -------------------------- Assignments --------------------------

    @PreAuthorize("@perm.can('lms.content.view')")
    @GetMapping("/assignments")
    public List<AssignmentDto> assignments(@RequestParam UUID sectionId) {
        return repo.listAssignments(sectionId);
    }

    public record CreateAssignmentRequest(
        @NotNull UUID schoolId, @NotNull UUID sectionId, @NotNull UUID subjectId, @NotBlank String title,
        String instructions, String submissionType, Instant dueAt, Double maxMarks, UUID createdByStaffId
    ) {}

    @PreAuthorize("@perm.can('lms.content.manage')")
    @PostMapping("/assignments")
    public AssignmentDto createAssignment(@RequestBody CreateAssignmentRequest req) {
        return repo.createAssignment(
            req.schoolId(), req.sectionId(), req.subjectId(), req.title(), req.instructions(),
            req.submissionType(), req.dueAt(), req.maxMarks(), req.createdByStaffId()
        );
    }

    @PreAuthorize("@perm.canAny('lms.grade', 'lms.content.view')")
    @GetMapping("/assignments/{id}/submissions")
    public List<AssignmentSubmissionDto> submissions(@PathVariable UUID id) {
        return repo.listSubmissions(id);
    }

    public record SubmitRequest(@NotNull UUID studentId, String body) {}

    @PreAuthorize("@perm.canAny('lms.grade', 'lms.submit')")
    @PostMapping("/assignments/{id}/submissions")
    public AssignmentSubmissionDto submit(@PathVariable UUID id, @RequestBody SubmitRequest req) {
        return repo.submit(id, req.studentId(), req.body());
    }

    public record GradeRequest(double marks, String feedback, UUID gradedByStaffId) {}

    @PreAuthorize("@perm.can('lms.grade')")
    @PostMapping("/submissions/{id}/grade")
    public AssignmentSubmissionDto grade(@PathVariable UUID id, @RequestBody GradeRequest req) {
        return repo.grade(id, req.marks(), req.feedback(), req.gradedByStaffId());
    }

    // -------------------------- Quiz --------------------------

    @PreAuthorize("@perm.can('lms.content.view')")
    @GetMapping("/quizzes")
    public List<QuizDto> quizzes(@RequestParam UUID schoolId) {
        return repo.listQuizzes(schoolId);
    }

    public record CreateQuizRequest(
        @NotNull UUID schoolId, UUID subjectId, @NotBlank String title, Integer durationMinutes,
        boolean randomise, boolean lockdown, UUID createdByStaffId
    ) {}

    @PreAuthorize("@perm.can('lms.content.manage')")
    @PostMapping("/quizzes")
    public QuizDto createQuiz(@RequestBody CreateQuizRequest req) {
        return repo.createQuiz(
            req.schoolId(), req.subjectId(), req.title(), req.durationMinutes(), req.randomise(), req.lockdown(), req.createdByStaffId()
        );
    }

    @PreAuthorize("@perm.can('lms.content.view')")
    @GetMapping("/quizzes/{id}/questions")
    public List<QuizQuestionDto> questions(@PathVariable UUID id) {
        return repo.listQuestions(id);
    }

    public record AddQuestionRequest(
        UUID curriculumNodeId, @NotBlank String kind, @NotBlank String prompt,
        Object options, Object answer, double marks, int sortOrder
    ) {}

    @PreAuthorize("@perm.can('lms.content.manage')")
    @PostMapping("/quizzes/{id}/questions")
    public QuizQuestionDto addQuestion(@PathVariable UUID id, @RequestBody AddQuestionRequest req) {
        return repo.addQuestion(id, req.curriculumNodeId(), req.kind(), req.prompt(), req.options(), req.answer(), req.marks(), req.sortOrder());
    }

    @PreAuthorize("@perm.canAny('lms.grade', 'lms.submit')")
    @GetMapping("/quizzes/{id}/attempts")
    public List<QuizAttemptDto> attempts(@PathVariable UUID id) {
        return repo.listAttempts(id);
    }

    public record StartAttemptRequest(@NotNull UUID studentId) {}

    @PreAuthorize("@perm.canAny('lms.grade', 'lms.submit')")
    @PostMapping("/quizzes/{id}/attempts")
    public QuizAttemptDto startAttempt(@PathVariable UUID id, @RequestBody StartAttemptRequest req) {
        return repo.startAttempt(id, req.studentId());
    }

    public record SubmitAttemptRequest(Object responses, double score) {}

    @PreAuthorize("@perm.canAny('lms.grade', 'lms.submit')")
    @PostMapping("/attempts/{id}/submit")
    public QuizAttemptDto submitAttempt(@PathVariable UUID id, @RequestBody SubmitAttemptRequest req) {
        return repo.submitAttempt(id, req.responses(), req.score());
    }
}
