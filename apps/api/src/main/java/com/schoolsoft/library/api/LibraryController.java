package com.schoolsoft.library.api;

import org.springframework.security.access.prepost.PreAuthorize;
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

    @PreAuthorize("@perm.can('library.view')")
    @GetMapping("/titles")
    public List<LibraryTitleDto> titles(@RequestParam UUID schoolId, @RequestParam(required = false) String q) {
        return repo.listTitles(schoolId, q);
    }

    public record CreateTitleRequest(
        String isbn, @NotBlank String title, String author, String publisher, Integer year, List<String> subjectTags
    ) {}

    @PreAuthorize("@perm.can('library.manage')")
    @PostMapping("/titles")
    public LibraryTitleDto createTitle(@RequestParam UUID schoolId, @RequestBody CreateTitleRequest req) {
        return repo.createTitle(schoolId, req.isbn(), req.title(), req.author(), req.publisher(), req.year(), req.subjectTags());
    }

    @PreAuthorize("@perm.can('library.view')")
    @GetMapping("/titles/{titleId}/copies")
    public List<LibraryCopyDto> copies(@PathVariable UUID titleId) {
        return repo.listCopies(titleId);
    }

    public record AddCopyRequest(@NotBlank String barcode) {}

    @PreAuthorize("@perm.can('library.manage')")
    @PostMapping("/titles/{titleId}/copies")
    public LibraryCopyDto addCopy(@PathVariable UUID titleId, @RequestBody AddCopyRequest req) {
        return repo.addCopy(titleId, req.barcode());
    }

    public record IssueRequest(
        @NotNull UUID schoolId, @NotNull UUID copyId, @NotBlank String memberType, @NotNull UUID memberId, @NotNull LocalDate dueOn
    ) {}

    @PreAuthorize("@perm.can('library.circulate')")
    @PostMapping("/issues")
    public LibraryIssueDto issue(@RequestBody IssueRequest req) {
        return repo.issue(req.schoolId(), req.copyId(), req.memberType(), req.memberId(), req.dueOn());
    }

    @PreAuthorize("@perm.can('library.circulate')")
    @PostMapping("/issues/{id}/return")
    public LibraryIssueDto returnCopy(@PathVariable UUID id) {
        return repo.returnCopy(id);
    }

    public record ChargeCopyRequest(@NotBlank String kind, Double amount, String notes) {}

    /** Charges a lost or damaged copy to the member's fee account (LIB-04). */
    @PreAuthorize("@perm.can('library.circulate')")
    @PostMapping("/issues/{id}/charge")
    public LibraryIssueDto chargeCopy(@PathVariable UUID id, @RequestBody ChargeCopyRequest req) {
        return repo.chargeLostOrDamaged(id, req.kind(), req.amount(), req.notes());
    }

    @PreAuthorize("@perm.can('library.view')")
    @GetMapping("/issues/active")
    public List<LibraryIssueDto> activeForMember(@RequestParam String memberType, @RequestParam UUID memberId) {
        return repo.listActiveForMember(memberType, memberId);
    }
}
