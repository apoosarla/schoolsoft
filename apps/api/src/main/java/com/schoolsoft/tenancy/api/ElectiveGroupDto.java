package com.schoolsoft.tenancy.api;

import java.util.List;
import java.util.UUID;

/**
 * An option block a grade offers in an academic year — an IGCSE block, a
 * Class 11 stream. {@code minPicks}/{@code maxPicks} say how many of the
 * options a student takes from it.
 */
public record ElectiveGroupDto(
    UUID id,
    UUID schoolId,
    UUID academicYearId,
    UUID gradeId,
    String code,
    String name,
    int minPicks,
    int maxPicks,
    List<Option> options
) {
    public record Option(UUID subjectId, String subjectCode, String subjectName, Integer capacity) {}
}
