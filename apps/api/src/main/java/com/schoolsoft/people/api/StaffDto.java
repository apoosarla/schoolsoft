package com.schoolsoft.people.api;

import java.time.LocalDate;
import java.util.UUID;

public record StaffDto(
    UUID id,
    UUID schoolId,
    String employeeNo,
    String firstName,
    String lastName,
    String email,
    String phone,
    String employmentType,
    LocalDate joinedOn,
    boolean isActive,
    UUID campusId,
    /**
     * Last working day, inclusive; null while they are on the books. This, not
     * {@code isActive}, is what says whether somebody still works here — a
     * date in the future is a person serving notice.
     */
    LocalDate leftOn,
    String exitReason,
    /** Who took over their sections and periods, when there were any. */
    UUID successorStaffId,
    /** Sent back on an edit or an exit; a stale one is refused with 409. */
    int version
) {}
