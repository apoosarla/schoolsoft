package com.schoolsoft.publicsite.api;

import java.time.Instant;
import java.time.LocalDate;

/**
 * What a family sees when it tracks an application: where it stands, and the
 * number to quote. Not the whole application — the tracking page answered with
 * the child's date of birth, the guardian's name and phone and the school's
 * internal ids to anybody holding a number and a phone that matched.
 */
public record PublicApplicationStatusDto(
    String applicationNo,
    String applicantFirstName,
    String applicantLastName,
    String state,
    String source,
    Double testScore,
    LocalDate offerExpiresOn,
    Instant createdAt
) {}
