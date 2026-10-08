package com.schoolsoft.admissions.api;

import java.util.UUID;

/**
 * Whether the family of an admitted child can sign in.
 *
 * <p>A sign-in address or number belongs to one account across the whole
 * chain. A parent who already has a child at another of the chain's schools,
 * or who works at this one, has theirs taken — so the child is admitted, the
 * guardian is recorded, and the login waits for an address or number that is
 * free. {@code hasLogin} false is that wait, and {@code contactPhone} /
 * {@code contactEmail} are what the family gave, which is what was refused.</p>
 */
public record GuardianLoginDto(
    UUID guardianId,
    String guardianName,
    boolean hasLogin,
    String signInPhone,
    String signInEmail,
    String contactPhone,
    String contactEmail
) {}
