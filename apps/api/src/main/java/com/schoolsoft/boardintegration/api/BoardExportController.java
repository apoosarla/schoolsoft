package com.schoolsoft.boardintegration.api;

import com.schoolsoft.boardintegration.internal.BoardExportRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/board-integration/exports")
public class BoardExportController {

    private final BoardExportRepository repo;
    public BoardExportController(BoardExportRepository repo) { this.repo = repo; }

    @GetMapping
    public List<BoardExportJobDto> list(@RequestParam UUID schoolId, @RequestParam(required = false) String status) {
        return repo.list(schoolId, status);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BoardExportJobDto> get(@PathVariable UUID id) {
        return repo.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    public record EnqueueRequest(
        @NotNull UUID schoolId, @NotBlank String boardCode, @NotBlank String exportType,
        UUID academicYearId, UUID sectionId, UUID studentId, Map<String, Object> requestPayload
    ) {}

    @PostMapping
    public BoardExportJobDto enqueue(@RequestBody EnqueueRequest req) {
        return repo.enqueue(
            req.schoolId(), req.boardCode(), req.exportType(),
            req.academicYearId(), req.sectionId(), req.studentId(), req.requestPayload()
        );
    }

    @PostMapping("/{id}/process")
    public BoardExportJobDto process(@PathVariable UUID id) {
        return repo.process(id);
    }

    public record FailRequest(@NotBlank String errorMessage) {}

    @PostMapping("/{id}/fail")
    public BoardExportJobDto fail(@PathVariable UUID id, @RequestBody FailRequest req) {
        return repo.fail(id, req.errorMessage());
    }
}
