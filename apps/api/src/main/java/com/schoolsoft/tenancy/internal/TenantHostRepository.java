package com.schoolsoft.tenancy.internal;

import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.tenancy.api.TenantResolutionDto;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Resolves a hostname to the one tenant that answers on it.
 *
 * Same shape as {@code publicsite.internal.PublicLookupRepository}, and for the
 * same reasons: {@code platformJdbc} runs with no {@link TenantContext} set and
 * therefore against {@code platform}, where {@code tenant_host} and
 * {@code chain} live; the per-tenant read builds a fresh {@code JdbcTemplate}
 * against the raw {@link DataSource} *after* setting the context and clears it
 * in a {@code finally}. This class must never become {@code @Transactional} —
 * a connection bound before the context is set would read the platform schema
 * and quietly find no school.
 */
@Repository
public class TenantHostRepository {

    private static final String HOST_SQL = """
        SELECT h.kind, h.school_id, c.id AS chain_id, c.slug AS chain_slug,
               c.name AS chain_name, c.schema_name
          FROM platform.tenant_host h
          JOIN platform.chain c ON c.id = h.chain_id
         WHERE h.host = ? AND c.status = 'active'
        """;

    private static final String SCHOOL_SQL = """
        SELECT s.slug, s.name,
               t.primary_color, t.accent_color, t.parent_app_name
          FROM school s
          LEFT JOIN school_theme t ON t.school_id = s.id
         WHERE s.id = ? AND s.is_active
        """;

    private final JdbcTemplate platformJdbc;
    private final DataSource dataSource;

    public TenantHostRepository(JdbcTemplate platformJdbc, DataSource dataSource) {
        this.platformJdbc = platformJdbc;
        this.dataSource = dataSource;
    }

    private record HostRow(String kind, UUID schoolId, UUID chainId,
                           String chainSlug, String chainName, String schemaName) {}

    private record SchoolRow(String slug, String name, String primary, String accent, String appName) {}

    /**
     * @param host the request's host, already stripped of any port by the caller
     * @param vendorBranded whether this host is one of ours rather than the school's own
     */
    public Optional<TenantResolutionDto> resolve(String host, boolean vendorBranded) {
        String folded = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (folded.isEmpty()) return Optional.empty();

        List<HostRow> hosts = platformJdbc.query(HOST_SQL, (rs, i) -> new HostRow(
            rs.getString("kind"),
            rs.getString("school_id") == null ? null : UUID.fromString(rs.getString("school_id")),
            UUID.fromString(rs.getString("chain_id")),
            rs.getString("chain_slug"),
            rs.getString("chain_name"),
            rs.getString("schema_name")
        ), folded);
        if (hosts.isEmpty()) return Optional.empty();
        HostRow h = hosts.get(0);

        if (h.schoolId() == null) {
            return Optional.of(new TenantResolutionDto(
                h.kind(), h.chainSlug(), h.chainName(),
                null, null, null, null, null, null, vendorBranded));
        }

        SchoolRow s = withTenant(h, jdbc -> {
            var rows = jdbc.query(SCHOOL_SQL, (rs, i) -> new SchoolRow(
                rs.getString("slug"), rs.getString("name"),
                rs.getString("primary_color"), rs.getString("accent_color"),
                rs.getString("parent_app_name")
            ), h.schoolId());
            return rows.isEmpty() ? null : rows.get(0);
        });

        // A host pointing at a school that has since been deactivated resolves
        // to nothing at all, rather than to a door with no name on it.
        if (s == null) return Optional.empty();

        return Optional.of(new TenantResolutionDto(
            h.kind(), h.chainSlug(), h.chainName(),
            h.schoolId(), s.slug(), s.name(),
            s.primary(), s.accent(), s.appName(), vendorBranded));
    }

    private <T> T withTenant(HostRow h, java.util.function.Function<JdbcTemplate, T> work) {
        TenantContext.set(TenantContext.trustedJob(h.schemaName(), h.chainId()));
        try {
            return work.apply(new JdbcTemplate(dataSource));
        } finally {
            TenantContext.clear();
        }
    }
}
