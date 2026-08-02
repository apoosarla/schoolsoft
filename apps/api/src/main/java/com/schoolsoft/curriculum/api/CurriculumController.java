package com.schoolsoft.curriculum.api;

import com.schoolsoft.curriculum.internal.CurriculumRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/curriculum")
public class CurriculumController {

    private final CurriculumRepository repo;
    public CurriculumController(CurriculumRepository repo) { this.repo = repo; }

    // -------------------------- Templates --------------------------

    @GetMapping("/templates")
    public List<CurriculumTemplateDto> templates(@RequestParam(required = false) String boardCode) {
        return repo.listTemplates(boardCode);
    }

    public record CloneTemplateRequest(@NotNull UUID schoolId, @NotNull UUID templateId, UUID gradeId, UUID subjectId) {}

    @PostMapping("/clone-from-template")
    public CurriculumDto cloneFromTemplate(@RequestBody CloneTemplateRequest req) {
        return repo.cloneFromTemplate(req.schoolId(), req.templateId(), req.gradeId(), req.subjectId());
    }

    // -------------------------- Curriculum --------------------------

    @GetMapping
    public List<CurriculumDto> list(@RequestParam UUID schoolId) {
        return repo.listCurricula(schoolId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CurriculumDto> get(@PathVariable UUID id) {
        return repo.findCurriculum(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    public record CreateCurriculumRequest(
        @NotNull UUID schoolId, @NotBlank String boardCode, @NotBlank String strategyCode,
        @NotBlank String name, @NotBlank String version, UUID gradeId, UUID subjectId
    ) {}

    @PostMapping
    public CurriculumDto create(@RequestBody CreateCurriculumRequest req) {
        return repo.createCurriculum(
            req.schoolId(), req.boardCode(), req.strategyCode(), req.name(), req.version(), req.gradeId(), req.subjectId()
        );
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Void> publish(@PathVariable UUID id) {
        repo.publish(id);
        return ResponseEntity.noContent().build();
    }

    // -------------------------- Nodes --------------------------

    @GetMapping("/{id}/nodes")
    public List<CurriculumNodeDto> nodes(@PathVariable UUID id) {
        return repo.listNodes(id);
    }

    public record CreateNodeRequest(
        UUID parentId, @NotBlank String nodeType, String code, @NotBlank String name, int sortOrder
    ) {}

    @PostMapping("/{id}/nodes")
    public CurriculumNodeDto addNode(@PathVariable UUID id, @RequestBody CreateNodeRequest req) {
        return repo.addNode(id, req.parentId(), req.nodeType(), req.code(), req.name(), req.sortOrder());
    }

    // -------------------------- Learning Outcomes --------------------------

    @GetMapping("/nodes/{nodeId}/learning-outcomes")
    public List<LearningOutcomeDto> learningOutcomes(@PathVariable UUID nodeId) {
        return repo.listLearningOutcomes(nodeId);
    }

    public record CreateLearningOutcomeRequest(String code, @NotBlank String statement, String bloomLevel, int sortOrder) {}

    @PostMapping("/nodes/{nodeId}/learning-outcomes")
    public LearningOutcomeDto addLearningOutcome(@PathVariable UUID nodeId, @RequestBody CreateLearningOutcomeRequest req) {
        return repo.addLearningOutcome(nodeId, req.code(), req.statement(), req.bloomLevel(), req.sortOrder());
    }
}
