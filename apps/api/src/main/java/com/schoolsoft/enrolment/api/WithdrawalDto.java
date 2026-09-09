package com.schoolsoft.enrolment.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A child's exit, with the checklist that gates it.
 *
 * <p>{@code state} moves {@code clearance_pending → cleared → completed}, and
 * {@code items} is why it is where it is: a withdrawal sits in
 * {@code clearance_pending} because something is blocked, and the row that
 * blocks it says so in its own words.</p>
 */
public record WithdrawalDto(
    UUID id,
    UUID schoolId,
    UUID studentId,
    UUID enrolmentId,
    String reasonCode,
    String reason,
    LocalDate requestedOn,
    LocalDate lastWorkingDate,
    String state,
    String duesOverrideReason,
    UUID duesOverrideByStaffId,
    Instant completedAt,
    String cancelledReason,
    List<ClearanceItemDto> items
) {
    /** True when nothing on the checklist is still blocking. */
    public boolean clear() {
        return items.stream().noneMatch(ClearanceItemDto::blocking);
    }
}
