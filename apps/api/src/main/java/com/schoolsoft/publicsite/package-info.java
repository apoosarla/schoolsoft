/**
 * The only module in this codebase reachable without a JWT — backs
 * {@code apps/public-site} (school info + admissions lead capture). Every
 * other module trusts {@link com.schoolsoft.platform.tenancy.TenantContext}
 * to already be populated by {@code TenantResolverFilter} parsing a bearer
 * token before a controller method runs; here there is no token, so
 * {@link com.schoolsoft.publicsite.internal.PublicLookupRepository} resolves
 * a chain slug to a schema itself and sets {@code TenantContext} manually,
 * mirroring {@code iam.internal.UserLookupService} — see that class's
 * Javadoc for why this must stay non-{@code @Transactional} with an
 * explicit try/finally clear.
 */
package com.schoolsoft.publicsite;
