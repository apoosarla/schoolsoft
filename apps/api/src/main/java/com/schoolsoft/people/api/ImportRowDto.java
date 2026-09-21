package com.schoolsoft.people.api;

import java.util.List;
import java.util.UUID;

/**
 * One line of a student spreadsheet, as the preview understood it.
 *
 * <p>The fields are what will be written; {@code errors} is why it will not
 * be. A row is shown back with its line number because the office is going to
 * fix the file, and "row 47" is the only address they have for it.</p>
 *
 * <p>{@code sectionId} and {@code academicYearId} are resolved at preview from
 * the grade and section codes in the file, so the commit writes what was
 * approved rather than looking the codes up again against a school that may
 * have changed underneath.</p>
 */
public record ImportRowDto(
    int line,
    String admissionNo,
    String firstName,
    String middleName,
    String lastName,
    String dob,
    String gender,
    String gradeCode,
    String sectionCode,
    String rollNo,
    UUID sectionId,
    UUID academicYearId,
    String guardianName,
    String guardianRelation,
    String guardianPhone,
    String guardianEmail,
    List<String> errors
) {
    public boolean ok() { return errors == null || errors.isEmpty(); }
}
