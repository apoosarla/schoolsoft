package com.schoolsoft.assessment.api;

import java.util.UUID;

/**
 * One subject's outcome for one student over a term: what they scored, out of
 * what, and — when they did not score — why not.
 *
 * {@code status} is the whole point. A child who was absent for the paper, one
 * exempted from it, and one who scored zero are three different rows on a
 * report card and three different things to do with an average; a single
 * nullable number could only say two of them.
 */
public record SubjectResult(
    UUID subjectId,
    String subjectCode,
    String subjectName,
    String origin,
    Double marksObtained,
    Double maxMarks,
    Double percentage,
    String status,                 // marked | absent | medical_leave | exempt | not_assessed
    String gradeLetter,
    Boolean passing,
    int sortOrder
) {
    /** True when the subject carries a score that belongs in an aggregate. */
    public boolean counted() {
        return "marked".equals(status) && percentage != null;
    }

    public SubjectResult graded(String grade, Boolean isPassing) {
        return new SubjectResult(subjectId, subjectCode, subjectName, origin, marksObtained, maxMarks,
            percentage, status, grade, isPassing, sortOrder);
    }
}
