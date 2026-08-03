package com.schoolsoft.library.api;

import com.schoolsoft.library.internal.LibraryRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/library")
public class LibraryController {

    private final LibraryRepository repo;
    public LibraryController(LibraryRepository repo) { this.repo = repo; }

    @GetMapping("/titles")
    public List<LibraryTitleDto> titles(@RequestParam UUID schoolId, @RequestParam(required = false) String q) {
        return repo.listTitles(schoolId, q);
    }

    public record CreateTitleRequest(
        String isbn, @NotBlank String title, String author, String publisher, Integer year, List<String> subjectTags
    ) {}

    @PostMapping("/titles")
    public LibraryTitleDto createTitle(@RequestParam UUID schoolId, @RequestBody CreateTitleRequest req) {
        return repo.createTitle(schoolId, req.isbn(), req.title(), req.author(), req.publisher(), req.year(), req.subjectTags());
    }

    @GetMapping("/titles/{titleId}/copies")
    public List<LibraryCopyDto> copies(@PathVariable UUID titleId) {
        return repo.listCopies(titleId);
    }

    public record AddCopyRequest(@NotBlank String barcode) {}

    @PostMapping("/titles/{titleId}/copies")
    public LibraryCopyDto addCopy(@PathVariable UUID titleId, @RequestBody AddCopyRequest req) {
        return repo.addCopy(titleId, req.barcode());
    }

    public record IssueRequest(
        @NotNull UUID schoolId, @NotNull UUID copyId, @NotBlank String memberType, @NotNull UUID memberId, @NotNull LocalDate dueOn
    ) {}

    @PostMapping("/issues")
    public LibraryIssueDto issue(@RequestBody IssueRequest req) {
        return repo.issue(req.schoolId(), req.copyId(), req.memberType(), req.memberId(), req.dueOn());
    }

    @PostMapping("/issues/{id}/return")
    public LibraryIssueDto returnCopy(@PathVariable UUID id) {
        return repo.returnCopy(id);
    }

    @GetMapping("/issues/active")
    public List<LibraryIssueDto> activeForMember(@RequestParam String memberType, @RequestParam UUID memberId) {
        return repo.listActiveForMember(memberType, memberId);
    }
}
