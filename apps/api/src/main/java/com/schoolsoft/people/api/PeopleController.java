package com.schoolsoft.people.api;

import com.schoolsoft.people.internal.PeopleRepository;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/people")
public class PeopleController {

    private final PeopleRepository repo;
    public PeopleController(PeopleRepository repo) { this.repo = repo; }

    // -------------------------- Students --------------------------
    @GetMapping("/students")
    public List<StudentDto> students(
        @RequestParam UUID schoolId,
        @RequestParam(required = false) UUID sectionId,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return repo.listStudents(schoolId, sectionId, q, Math.min(limit, 500));
    }

    @GetMapping("/students/{id}")
    public ResponseEntity<StudentDto> student(@PathVariable UUID id) {
        return repo.findStudent(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    public record CreateStudentRequest(
        @NotBlank UUID schoolId,
        @NotBlank String admissionNo,
        @NotBlank String firstName,
        String middleName,
        String lastName,
        String dob,
        String gender
    ) {}

    @PostMapping("/students")
    public StudentDto createStudent(@RequestBody CreateStudentRequest req) {
        return repo.createStudent(req);
    }

    @GetMapping("/students/{id}/guardians")
    public List<GuardianDto> guardiansOf(@PathVariable UUID id) {
        return repo.guardiansOfStudent(id);
    }

    // -------------------------- Guardians --------------------------
    @GetMapping("/guardians")
    public List<GuardianDto> guardians(@RequestParam UUID schoolId, @RequestParam(required = false) String q) {
        return repo.listGuardians(schoolId, q);
    }

    @GetMapping("/guardians/{id}/students")
    public List<StudentDto> studentsOf(@PathVariable UUID id) {
        return repo.studentsOfGuardian(id);
    }

    // -------------------------- Staff --------------------------
    @GetMapping("/staff")
    public List<StaffDto> staff(@RequestParam UUID schoolId, @RequestParam(required = false) String q) {
        return repo.listStaff(schoolId, q);
    }

    // -------------------------- Directory --------------------------
    @GetMapping("/directory")
    public List<UserDirectoryEntryDto> directory(
        @RequestParam UUID schoolId,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String subjectType
    ) {
        return repo.listDirectory(schoolId, q, subjectType);
    }
}
