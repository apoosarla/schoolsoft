package com.schoolsoft.tenancy.api;

import com.schoolsoft.people.api.StaffDto;
import java.util.UUID;

/**
 * What handing a school its first keyholder produced: the person, the way
 * they sign in, the campus they were put on, and the checklist as it now
 * reads.
 *
 * <p>The readiness comes back with it because two of its steps have just
 * changed and the caller would otherwise have to ask again to find out — and
 * because the point of the act is the checklist moving.</p>
 */
public record SchoolHandoverDto(
    UUID schoolId,
    StaffDto staff,
    UUID userAccountId,
    UUID campusId,
    boolean campusCreated,
    String roleCode,
    String signsInWith,
    SchoolReadinessDto readiness
) {}
