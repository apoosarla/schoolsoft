package com.schoolsoft.assessment.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A report card's header (GAP-13).
 *
 * Everything a school, a family or the year-end rollover asks about a card is
 * a field here rather than a key inside a JSON blob: what the child scored,
 * how they placed, how much school they attended, whether they are promoted,
 * and which terms the card can honestly speak for.
 */
public record ReportCardDto(
    UUID id,
    UUID schoolId,
    UUID studentId,
    UUID sectionId,
    UUID academicYearId,
    UUID termId,
    String strategyCode,
    String templateCode,
    String status,
    int version,
    boolean isLocked,
    String gradeScaleCode,
    Double totalMarks,
    Double totalMaxMarks,
    Double overallPct,
    String overallGrade,
    Integer classRank,
    Integer classSize,
    Double percentile,
    Integer attendanceWorkingDays,
    Double attendancePresentDays,
    Double attendancePct,
    String promotionDecision,
    String teacherRemarks,
    String principalRemarks,
    LocalDate enrolledFrom,
    Integer termsAttended,
    Integer termsInYear,
    String coverageNote,
    Instant publishedAt,
    Instant generatedAt
) {}
