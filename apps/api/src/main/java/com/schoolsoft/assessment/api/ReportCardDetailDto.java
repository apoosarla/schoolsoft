package com.schoolsoft.assessment.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A report card as it is rendered: the header, the subject rows, the
 * co-scholastic ratings, and whatever board-specific extras the template
 * carries in {@code payload}.
 *
 * The subject rows are the student's own subjects, not their section's — with
 * option blocks those are different lists, and printing the section's is how a
 * Cambridge card ends up showing a paper the candidate never sat (ASMT-13).
 */
public record ReportCardDetailDto(
    ReportCardDto card,
    List<SubjectRow> subjects,
    List<CoScholasticRow> coScholastic,
    Map<String, Object> payload
) {
    /**
     * {@code resultStatus} is what the row prints: a marked subject shows its
     * score, an absence shows AB, an exemption shows EX. None of the three is
     * a zero, and only the first counts towards the aggregate (ASMT-05).
     */
    public record SubjectRow(
        UUID subjectId,
        String subjectCode,
        String subjectName,
        String origin,
        Double marksObtained,
        Double maxMarks,
        Double percentage,
        String gradeLetter,
        String resultStatus,
        String display,
        Boolean passing,
        String remarks,
        int sortOrder
    ) {}

    public record CoScholasticRow(
        String areaCode,
        String areaName,
        String rating,
        String remarks,
        int sortOrder
    ) {}
}
