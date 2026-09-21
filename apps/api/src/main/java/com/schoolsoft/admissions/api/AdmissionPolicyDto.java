package com.schoolsoft.admissions.api;

import java.util.UUID;

/**
 * Which funnel this school runs.
 *
 * <p>{@code entranceTestRequired} decides which moves out of {@code review}
 * exist: a school that tests goes {@code review -> test_scheduled -> test_done
 * -> offered}, a school that does not goes {@code review -> offered}. Neither
 * can take the other's route, so a school cannot skip its own assessment step
 * by accident and cannot schedule a test it does not hold.</p>
 *
 * <p>{@code offerValidityDays} is how long an offer stands, in days from the
 * day it is made. It is what fills {@code offer_expires_on}.</p>
 */
public record AdmissionPolicyDto(
    UUID schoolId,
    boolean entranceTestRequired,
    int offerValidityDays
) {}
