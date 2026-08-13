package com.schoolsoft.assessment.api;

import java.util.UUID;

/**
 * One student's mark on one component.
 *
 * {@code status} says why a number is or is not there: {@code entered} carries
 * a score (zero included), {@code pending} means nobody has marked it yet,
 * and {@code absent}, {@code medical_leave} and {@code exempt} each render
 * differently on a report card and are all excluded from averages (ASMT-04,
 * ASMT-05).
 *
 * {@code isAbsent} is kept as a derived convenience for the apps that read it,
 * but it is no longer the stored truth — a boolean could not tell a blank from
 * a zero.
 */
public record MarkDto(
    UUID id,
    UUID assessmentComponentId,
    UUID studentId,
    Double rawMarks,
    String gradeLetter,
    String remarks,
    String status,
    boolean isAbsent,
    int revisionCount
) {
    public static MarkDto of(UUID id, UUID componentId, UUID studentId, Double rawMarks,
                             String gradeLetter, String remarks, String status, int revisionCount) {
        return new MarkDto(id, componentId, studentId, rawMarks, gradeLetter, remarks,
            status, "absent".equals(status) || "medical_leave".equals(status), revisionCount);
    }
}
