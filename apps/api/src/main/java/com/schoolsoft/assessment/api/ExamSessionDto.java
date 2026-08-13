package com.schoolsoft.assessment.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One paper, sat by one grade, at one time, in one room.
 *
 * The grade rather than the section is the unit because a paper is set for a
 * cohort; which children actually sit it comes from their own subject sets,
 * which is why a clash is a per-student question (ASMT-09).
 */
public record ExamSessionDto(
    UUID id,
    UUID examScheduleId,
    UUID schoolId,
    UUID gradeId,
    UUID subjectId,
    String subjectCode,
    String subjectName,
    String paperCode,
    String name,
    LocalDate onDate,
    LocalTime startsAt,
    LocalTime endsAt,
    String room,
    UUID invigilatorStaffId,
    Double maxMarks,
    UUID assessmentId
) {}
