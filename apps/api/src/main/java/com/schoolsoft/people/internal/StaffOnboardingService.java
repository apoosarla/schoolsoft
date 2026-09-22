package com.schoolsoft.people.internal;

import com.schoolsoft.people.api.StaffDto;
import com.schoolsoft.people.api.StaffOnboarding;
import org.springframework.stereotype.Service;

/**
 * The published half of {@link StaffOnboarding}. Thin on purpose: the row is
 * the whole act, and the transaction it belongs to is the caller's — opening
 * a school creates a campus, a staff row, an account and a role grant, and
 * either all four happened or none did.
 */
@Service
public class StaffOnboardingService implements StaffOnboarding {

    private final PeopleRepository repo;

    public StaffOnboardingService(PeopleRepository repo) {
        this.repo = repo;
    }

    @Override
    public StaffDto create(NewStaff staff) {
        return repo.createStaff(staff);
    }
}
