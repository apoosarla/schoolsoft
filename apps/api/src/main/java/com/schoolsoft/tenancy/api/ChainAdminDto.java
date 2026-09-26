package com.schoolsoft.tenancy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A chain's HQ administrator: the account the chain signs in with, which
 * belongs to no school inside it.
 *
 * <p>There is no name here because there is no person row to take one from —
 * a {@code chain_admin} has no {@code staff} or {@code guardian} record, only
 * the address they sign in with. {@code signsInWith} is that address, chosen
 * the way the sign-in screen chooses it: the email if there is one, the mobile
 * number otherwise.</p>
 */
public record ChainAdminDto(
    UUID accountId,
    String email,
    String phone,
    String signsInWith,
    boolean active,
    Instant createdAt
) {}
