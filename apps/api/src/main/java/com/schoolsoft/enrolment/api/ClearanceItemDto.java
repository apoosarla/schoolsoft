package com.schoolsoft.enrolment.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of a leaver's checklist: an area of the school, and whether it is
 * willing to let go.
 *
 * <p>{@code waived} is kept distinct from {@code cleared} on purpose. Both let
 * the exit through, and only one of them means the debt was actually settled —
 * collapsing the two would leave the school unable to tell, a year later, which
 * of its leavers it wrote money off for.</p>
 */
public record ClearanceItemDto(
    UUID id,
    UUID withdrawalId,
    String area,
    String state,
    String detail,
    Double amount,
    Instant checkedAt,
    UUID resolvedByStaffId,
    String resolvedReason,
    Instant resolvedAt
) {
    /** True while this line stops the withdrawal completing. */
    public boolean blocking() {
        return "blocked".equals(state) || "pending".equals(state);
    }
}
