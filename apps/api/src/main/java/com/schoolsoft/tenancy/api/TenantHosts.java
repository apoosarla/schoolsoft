package com.schoolsoft.tenancy.api;

import java.util.UUID;

/**
 * The narrow write side of the hostname registry, published so that
 * {@code theming} can keep a school's custom domain resolvable without
 * reaching into tenancy's repositories.
 *
 * Deliberately three methods and no reads: the module that sets a domain has
 * no business enumerating the hosts of other tenants, and the public resolver
 * is the only reader there is.
 */
public interface TenantHosts {

    /** The chain's own HQ door, {@code <chain>.<suffix>}. Idempotent. */
    void registerChainHq(UUID chainId, String chainSlug);

    /** A school's door on our domain, {@code <school>.<chain>.<suffix>}. Idempotent. */
    void registerSchool(UUID chainId, UUID schoolId, String chainSlug, String schoolSlug);

    /**
     * Point a school's own domain at it, replacing whatever domain it had.
     * A blank host clears it, which is how a school leaves its own domain
     * without leaving a dangling row that still answers.
     */
    void setCustomDomain(UUID chainId, UUID schoolId, String host);
}
