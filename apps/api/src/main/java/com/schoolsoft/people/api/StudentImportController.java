package com.schoolsoft.people.api;

import com.schoolsoft.audit.api.Audited;
import com.schoolsoft.people.internal.StudentImportService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The spreadsheet door (GAP-23, ENR-09).
 *
 * <p>The file arrives as text in a JSON body rather than as a multipart
 * upload: the client already has to read it to show a preview, a register of
 * a few thousand children is a few hundred kilobytes, and it keeps one
 * content type across the whole API. A file large enough for that to matter
 * is a file that wants a job, not a request.</p>
 *
 * <p>Both writes are {@code student.import} — its own permission rather than
 * a reading of {@code student.manage} plus {@code enrolment.manage}, because
 * the gate grammar has no "and" and an import that could write children but
 * not enrol them would leave the register half-built.</p>
 */
@RestController
@RequestMapping("/v1/people/imports")
public class StudentImportController {

    private final StudentImportService imports;

    public StudentImportController(StudentImportService imports) {
        this.imports = imports;
    }

    public record PreviewRequest(
        @NotNull UUID schoolId,
        String filename,
        @NotBlank String csv
    ) {}

    /**
     * Parses and checks the file and writes nothing. The answer is the file as
     * the server understood it, row by row, with every problem named at once —
     * an office fixing a spreadsheet wants the whole list, not the first error.
     */
    @PreAuthorize("@perm.can('student.import')")
    @PostMapping("/students/preview")
    public ImportBatchDto preview(@RequestBody PreviewRequest req) {
        return imports.preview(req.schoolId(), req.filename(), req.csv());
    }

    /**
     * Imports the batch that was previewed — not the file, the batch — so what
     * the office approved is what lands.
     */
    @PreAuthorize("@perm.can('student.import')")
    @Audited(action = "student.imported", targetType = "import_batch", idParam = "id",
             snapshot = false, requireReason = false)
    @PostMapping("/{id}/commit")
    public ImportResultDto commit(@PathVariable UUID id) {
        return imports.commit(id);
    }

    @PreAuthorize("@perm.can('student.import')")
    @GetMapping("/{id}")
    public ImportBatchDto get(@PathVariable UUID id) {
        return imports.find(id);
    }

    /** Recent imports for a school, without the rows: this is the history, not the files. */
    @PreAuthorize("@perm.can('student.import')")
    @GetMapping
    public List<ImportBatchDto> recent(@RequestParam UUID schoolId) {
        return imports.recent(schoolId);
    }
}
