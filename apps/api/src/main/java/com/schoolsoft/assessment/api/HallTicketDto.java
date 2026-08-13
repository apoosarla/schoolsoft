package com.schoolsoft.assessment.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a candidate walks in with: their number, their seat, and the papers
 * <em>they</em> sit — resolved from their own subject set, so two students in
 * one section with different options carry different tickets.
 */
public record HallTicketDto(
    UUID id,
    UUID examScheduleId,
    UUID studentId,
    String studentName,
    String admissionNo,
    String ticketNo,
    String seatNo,
    Instant issuedAt,
    List<ExamSessionDto> sessions
) {}
