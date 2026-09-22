package com.schoolsoft.people.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Putting a member of staff on a school's books, for the one caller that has
 * to do it from outside the school: the chain's HQ, handing a newly opened
 * school to the person who will run it.
 *
 * <p>Exists for the reason {@code PublicAdmissions} does — so {@code tenancy}
 * names a minimal surface instead of holding {@code PeopleRepository} and
 * being able to reach the student register from the school-opening flow. It
 * is deliberately one method: this is not a staff directory, and the office's
 * own hiring belongs on the school's screens, not the chain's.</p>
 */
public interface StaffOnboarding {

    /**
     * {@code campusId} is required because {@code staff.campus_id} is — a
     * person works somewhere. The caller resolves the campus; a school with
     * none has to be given one first, which is why the first campus and the
     * first keyholder are one act at the tenancy end.
     */
    record NewStaff(
        UUID schoolId,
        UUID campusId,
        String employeeNo,
        String firstName,
        String lastName,
        String email,
        String phone,
        String employmentType,
        LocalDate joinedOn
    ) {}

    StaffDto create(NewStaff staff);
}
