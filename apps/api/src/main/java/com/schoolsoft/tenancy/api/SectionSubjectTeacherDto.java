package com.schoolsoft.tenancy.api;

import java.time.LocalDate;
import java.util.UUID;

public record SectionSubjectTeacherDto(
    UUID id,
    UUID sectionId,
    UUID subjectId,
    String subjectName,
    UUID teacherStaffId,
    String teacherName,
    boolean isPrimary,
    /** True when the section is taught this subject only to the students who elected it. */
    boolean isElective,
    /** First day the teacher holds the assignment; null when it has always been theirs. */
    LocalDate effectiveFrom,
    /** Last day they hold it, inclusive; null while it is open-ended. */
    LocalDate effectiveTo
) {}
