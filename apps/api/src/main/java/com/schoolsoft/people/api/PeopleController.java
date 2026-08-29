package com.schoolsoft.people.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.iam.api.SelfScope;
import com.schoolsoft.people.internal.PeopleRepository;
import com.schoolsoft.platform.security.Perm;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/people")
public class PeopleController {

    private final PeopleRepository repo;
    private final SelfScope selfScope;

    public PeopleController(PeopleRepository repo, SelfScope selfScope) {
        this.repo = repo;
        this.selfScope = selfScope;
    }

    // -------------------------- Students --------------------------
    @PreAuthorize("@perm.can('student.view')")
    @GetMapping("/students")
    public List<StudentDto> students(
        @RequestParam UUID schoolId,
        @RequestParam(required = false) UUID sectionId,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return repo.listStudents(schoolId, sectionId, q, Math.min(limit, 500));
    }

    @PreAuthorize("@perm.canAnyOf('student.view', 'student.view.own')")
    @GetMapping("/students/{id}")
    public ResponseEntity<StudentDto> student(@PathVariable UUID id) {
        selfScope.requireStudent(id, Perm.STUDENT_VIEW);
        return repo.findStudent(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    public record CreateStudentRequest(
        @NotNull UUID schoolId,
        /** Optional — the school's admission-number series issues one when omitted. */
        String admissionNo,
        @NotBlank String firstName,
        String middleName,
        String lastName,
        String dob,
        String gender
    ) {}

    @PreAuthorize("@perm.can('student.manage')")
    @PostMapping("/students")
    public StudentDto createStudent(@RequestBody CreateStudentRequest req) {
        return repo.createStudent(req);
    }

    @PreAuthorize("@perm.canAnyOf('guardian.view', 'student.view.own')")
    @GetMapping("/students/{id}/guardians")
    public List<GuardianDto> guardiansOf(@PathVariable UUID id) {
        // A guardian may see who else is on their own child's record — the
        // other parent, the emergency contact — and nobody else's.
        selfScope.requireStudent(id, Perm.GUARDIAN_VIEW);
        return repo.guardiansOfStudent(id);
    }

    // -------------------------- Guardians --------------------------
    @PreAuthorize("@perm.can('guardian.view')")
    @GetMapping("/guardians")
    public List<GuardianDto> guardians(@RequestParam UUID schoolId, @RequestParam(required = false) String q) {
        return repo.listGuardians(schoolId, q);
    }

    @PreAuthorize("@perm.canAnyOf('guardian.view', 'student.view.own')")
    @GetMapping("/guardians/{id}/students")
    public List<StudentDto> studentsOf(@PathVariable UUID id) {
        selfScope.requireGuardian(id, Perm.GUARDIAN_VIEW);
        return repo.studentsOfGuardian(id);
    }

    // -------------------------- Staff --------------------------
    @PreAuthorize("@perm.can('staff.view')")
    @GetMapping("/staff")
    public List<StaffDto> staff(@RequestParam UUID schoolId, @RequestParam(required = false) String q) {
        return repo.listStaff(schoolId, q);
    }

    // -------------------------- Directory --------------------------
    @PreAuthorize("@perm.can('directory.view')")
    @GetMapping("/directory")
    public List<UserDirectoryEntryDto> directory(
        @RequestParam UUID schoolId,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String subjectType
    ) {
        return repo.listDirectory(schoolId, q, subjectType);
    }
}
