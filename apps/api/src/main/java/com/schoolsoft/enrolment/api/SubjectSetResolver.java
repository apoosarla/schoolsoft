package com.schoolsoft.enrolment.api;

import com.schoolsoft.enrolment.internal.StudentSubjectRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The single answer to "what does this student study?" (GAP-05).
 *
 * <pre>
 *   subject set = the section's compulsory subjects + the student's elections
 * </pre>
 *
 * Every module that used to assume a section-wide subject set asks here
 * instead: marks entry refuses a subject the student does not take, the report
 * card lists their subjects rather than their section's, the timetable can be
 * filtered to a student's own periods, and a board export carries each
 * student's real option block. Two Cambridge students in one section legitimately
 * sit different papers; before this, the system could not say so.
 */
@Service
public class SubjectSetResolver {

    private final StudentSubjectRepository repo;

    public SubjectSetResolver(StudentSubjectRepository repo) { this.repo = repo; }

    /** Subjects for a specific enrolment, as of {@code onDate}. */
    public List<StudentSubjectDto> forEnrolment(UUID enrolmentId, LocalDate onDate) {
        return repo.resolveForEnrolment(enrolmentId, onDate == null ? LocalDate.now() : onDate);
    }

    /**
     * Subjects for a student on a date, resolved through the enrolment they
     * held then — so a report card for last term reflects what they were
     * taking then, not what they take now.
     */
    public List<StudentSubjectDto> forStudent(UUID studentId, LocalDate onDate) {
        LocalDate date = onDate == null ? LocalDate.now() : onDate;
        UUID enrolmentId = repo.enrolmentIdFor(studentId, date);
        return enrolmentId == null ? List.of() : repo.resolveForEnrolment(enrolmentId, date);
    }

    /**
     * Every active student in a grade with the subjects they take, in one
     * query — for callers that have to reason about a cohort rather than a
     * child, exam clash detection being the first of them (ASMT-09).
     */
    public java.util.Map<UUID, java.util.Set<UUID>> subjectsByStudentInGrade(
        UUID gradeId, UUID academicYearId, LocalDate onDate
    ) {
        var byStudent = new java.util.LinkedHashMap<UUID, java.util.Set<UUID>>();
        for (StudentSubjectDto row : repo.resolveForGrade(gradeId, academicYearId,
                onDate == null ? LocalDate.now() : onDate)) {
            byStudent.computeIfAbsent(row.studentId(), k -> new java.util.LinkedHashSet<>()).add(row.subjectId());
        }
        return byStudent;
    }

    public boolean studies(UUID studentId, UUID subjectId, LocalDate onDate) {
        return forStudent(studentId, onDate).stream().anyMatch(s -> s.subjectId().equals(subjectId));
    }

    /**
     * Throws when the student does not take the subject. Used by marks entry:
     * a mark against a subject a child never sat is a data-entry slip that is
     * very hard to spot later on a report card.
     */
    public void requireStudies(UUID studentId, UUID subjectId, LocalDate onDate) {
        if (!studies(studentId, subjectId, onDate)) {
            throw new IllegalArgumentException(
                "Student " + studentId + " does not take subject " + subjectId
                + " (their subject set is the section's compulsory subjects plus their elections)");
        }
    }
}
