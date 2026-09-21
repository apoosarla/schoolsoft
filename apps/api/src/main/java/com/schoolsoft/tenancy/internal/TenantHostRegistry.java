package com.schoolsoft.tenancy.internal;

import com.schoolsoft.tenancy.api.TenantHosts;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Keeps {@code platform.tenant_host} in step with the tenants that exist.
 *
 * Every write is an upsert on the host, because the alternative — a row per
 * attempt — would mean two tenants racing for one hostname, and the host is the
 * only thing a browser has to go on. The primary key settles it: last writer
 * owns the name, and a school that moves domain releases the old one.
 *
 * Writes go to {@code platform} explicitly rather than through the search
 * path, so the same method works whether it was called from inside a chain
 * schema (school creation) or outside one (chain provisioning).
 */
@Service
public class TenantHostRegistry implements TenantHosts {

    private static final String UPSERT = """
        INSERT INTO platform.tenant_host (host, chain_id, school_id, kind)
             VALUES (?, ?, ?, ?)
        ON CONFLICT (host) DO UPDATE
                SET chain_id = EXCLUDED.chain_id,
                    school_id = EXCLUDED.school_id,
                    kind = EXCLUDED.kind
        """;

    private final JdbcTemplate jdbc;
    private final String suffix;

    public TenantHostRegistry(
        JdbcTemplate jdbc,
        @Value("${schoolsoft.public.host-suffix:.schoolsoft.app}") String suffix
    ) {
        this.jdbc = jdbc;
        this.suffix = suffix.toLowerCase(Locale.ROOT);
    }

    @Override
    public void registerChainHq(UUID chainId, String chainSlug) {
        jdbc.update(UPSERT, fold(chainSlug + suffix), chainId, null, "chain_hq");
    }

    @Override
    public void registerSchool(UUID chainId, UUID schoolId, String chainSlug, String schoolSlug) {
        jdbc.update(UPSERT, fold(schoolSlug + "." + chainSlug + suffix), chainId, schoolId, "school");
    }

    @Override
    public void setCustomDomain(UUID chainId, UUID schoolId, String host) {
        // The school's previous domain stops answering the moment a new one is
        // set: a released hostname that still resolves is a door nobody owns.
        jdbc.update(
            "DELETE FROM platform.tenant_host WHERE school_id = ? AND host NOT LIKE ?",
            schoolId, "%" + suffix
        );
        if (host == null || host.isBlank()) return;
        jdbc.update(UPSERT, fold(host), chainId, schoolId, "school");
    }

    private static String fold(String host) {
        return host.trim().toLowerCase(Locale.ROOT);
    }
}
