package com.schoolsoft.publicsite.internal;

import com.schoolsoft.admissions.api.AdmissionApplicationDto;
import com.schoolsoft.admissions.internal.AdmissionsRepository;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.NotFoundException;
import com.schoolsoft.publicsite.api.PublicSchoolDto;
import com.schoolsoft.tenancy.api.GradeDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Resolves {@code (chainSlug, schoolSlug)} from an unauthenticated request to
 * a tenant schema, then runs the requested query inside that tenant. See the
 * package Javadoc — this is the one place in the app that sets
 * {@link TenantContext} from something other than a JWT.
 *
 * {@code platformJdbc} runs with no {@link TenantContext} set — the shared
 * {@code JdbcTemplate}/{@code TenantAwareDataSource} pair falls back to the
 * platform schema in that state (see {@code DataSourceConfig}), which is
 * exactly where {@code platform.chain} lives. Every tenant-scoped query below
 * instead builds a fresh {@code JdbcTemplate} against the raw
 * {@link DataSource} *after* setting {@link TenantContext}, and clears it in
 * a {@code finally} — same pattern as {@code iam.internal.UserLookupService},
 * for the same reason (see that class's Javadoc): this class must never
 * become {@code @Transactional}, or a connection would be bound before
 * {@link TenantContext} is set and every query here would silently run
 * against the platform schema instead of the resolved tenant.
 */
@Repository
public class PublicLookupRepository {

    private final JdbcTemplate platformJdbc;
    private final DataSource dataSource;
    private final AdmissionsRepository admissionsRepo;

    public PublicLookupRepository(JdbcTemplate platformJdbc, DataSource dataSource, AdmissionsRepository admissionsRepo) {
        this.platformJdbc = platformJdbc;
        this.dataSource = dataSource;
        this.admissionsRepo = admissionsRepo;
    }

    private record ChainRow(UUID id, String schemaName) {}

    private Optional<ChainRow> resolveChain(String chainSlug) {
        var rows = platformJdbc.query(
            "SELECT id, schema_name FROM platform.chain WHERE slug = ? AND status = 'active'",
            (rs, i) -> new ChainRow(UUID.fromString(rs.getString("id")), rs.getString("schema_name")),
            chainSlug
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private <T> T withTenant(String chainSlug, java.util.function.Function<JdbcTemplate, T> work) {
        ChainRow chain = resolveChain(chainSlug)
            .orElseThrow(() -> new NotFoundException("No chain for slug: " + chainSlug));
        TenantContext.set(TenantContext.trustedJob(chain.schemaName(), chain.id()));
        try {
            return work.apply(new JdbcTemplate(dataSource));
        } finally {
            TenantContext.clear();
        }
    }

    private Optional<UUID> schoolIdForSlug(JdbcTemplate jdbc, String schoolSlug) {
        var rows = jdbc.query("SELECT id FROM school WHERE slug = ? AND is_active", (rs, i) -> UUID.fromString(rs.getString("id")), schoolSlug);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<PublicSchoolDto> findSchool(String chainSlug, String schoolSlug) {
        return withTenant(chainSlug, jdbc -> {
            var rows = jdbc.query(
                "SELECT id, slug, name, board_code FROM school WHERE slug = ? AND is_active",
                (rs, i) -> new PublicSchoolDto(
                    UUID.fromString(rs.getString("id")), rs.getString("slug"), rs.getString("name"), rs.getString("board_code")
                ),
                schoolSlug
            );
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        });
    }

    public List<GradeDto> listGrades(String chainSlug, String schoolSlug) {
        return withTenant(chainSlug, jdbc -> {
            UUID schoolId = schoolIdForSlug(jdbc, schoolSlug)
                .orElseThrow(() -> new NotFoundException("No school for slug: " + schoolSlug));
            return jdbc.query(
                "SELECT id, code, name, sort_order FROM grade WHERE school_id = ? ORDER BY sort_order",
                (rs, i) -> new GradeDto(UUID.fromString(rs.getString("id")), rs.getString("code"), rs.getString("name"), rs.getInt("sort_order")),
                schoolId
            );
        });
    }

    public record ApplyRequest(
        String applicantFirstName, String applicantLastName, LocalDate applicantDob, String applicantGender,
        UUID gradeId, String guardianName, String guardianPhone, String guardianEmail
    ) {}

    public Optional<AdmissionApplicationDto> track(String chainSlug, String applicationNo, String guardianPhone) {
        return withTenant(chainSlug, jdbc -> admissionsRepo.findByApplicationNoAndPhone(applicationNo, guardianPhone));
    }

    public AdmissionApplicationDto apply(String chainSlug, String schoolSlug, ApplyRequest req) {
        return withTenant(chainSlug, jdbc -> {
            UUID schoolId = schoolIdForSlug(jdbc, schoolSlug)
                .orElseThrow(() -> new NotFoundException("No school for slug: " + schoolSlug));
            UUID academicYearId = jdbc.queryForObject(
                "SELECT id FROM academic_year WHERE school_id = ? AND is_current LIMIT 1", UUID.class, schoolId
            );
            String applicationNo = "WEB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            return admissionsRepo.create(
                schoolId, academicYearId, req.gradeId(), applicationNo,
                req.applicantFirstName(), req.applicantLastName(), req.applicantDob(), req.applicantGender(),
                req.guardianName(), req.guardianPhone(), req.guardianEmail(), "website"
            );
        });
    }
}
