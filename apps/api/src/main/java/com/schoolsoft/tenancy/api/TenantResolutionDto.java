package com.schoolsoft.tenancy.api;

import java.util.UUID;

/**
 * What a sign-in page is allowed to know about its tenant before anybody has
 * proved who they are.
 *
 * Everything here is already on the school's own public site — its name, its
 * colours, the name it gives its parent app. Nothing here identifies a person,
 * and there is no endpoint that lists these: a caller gets one of these back
 * only by naming a host they already knew.
 *
 * {@code vendorBranded} is false when the school arrived on its own domain. On
 * that host the login page carries the school's name alone: which software a
 * school buys is the school's business to disclose, not ours to advertise.
 */
public record TenantResolutionDto(
    String kind,            // "school" | "chain_hq"
    String chainSlug,
    String chainName,
    UUID schoolId,
    String schoolSlug,
    String schoolName,
    String primaryColor,
    String accentColor,
    String appName,
    boolean vendorBranded
) {}
