package com.schoolsoft.tenancy.api;

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
    boolean isElective
) {}
