package com.schoolsoft.platform.tenancy;

import java.util.UUID;

/**
 * Holds the tenancy claims resolved from the inbound JWT for the duration of a
 * request. Stored in a {@link ThreadLocal} so any code reached from the request
 * thread (controllers, services, repositories) can read it without explicit
 * plumbing. Async hand-offs must propagate the context explicitly via
 * {@link #snapshot()} / {@link #restore(Snapshot)}.
 */
public final class TenantContext {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public record Snapshot(
        String chainSchema,        // 'chain_oakridge' | 'platform'
        UUID chainId,              // null when chainSchema = 'platform'
        UUID schoolId,             // null for chain-wide actors (HQ admin)
        UUID userAccountId,
        String subjectType,        // 'staff' | 'guardian' | 'student' | 'platform_admin'
        boolean trusted            // bypass RLS (jobs, migrations)
    ) {}

    public static void set(Snapshot s) { CURRENT.set(s); }

    public static Snapshot get() { return CURRENT.get(); }

    public static Snapshot require() {
        Snapshot s = CURRENT.get();
        if (s == null) {
            throw new IllegalStateException("Tenant context not set for this thread");
        }
        return s;
    }

    public static void clear() { CURRENT.remove(); }

    public static Snapshot platformAdmin(UUID userAccountId) {
        return new Snapshot("platform", null, null, userAccountId, "platform_admin", false);
    }

    public static Snapshot trustedJob(String chainSchema, UUID chainId) {
        return new Snapshot(chainSchema, chainId, null, null, "system", true);
    }
}
