package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolsoft.certification.support.AbstractCertificationTest;
import com.schoolsoft.platform.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-TEN — tenant & school onboarding. */
class TenancyOnboardingCertTest extends AbstractCertificationTest {

    private static final String PROBE_SLUG = "certprobe";

    @Test @Tag("P1")
    void cert_TEN_01_provisionChainCreatesMigratedSchemaAndEmptySchoolList() {
        dropProbeChain();

        var response = post("/v1/platform-admin/chains",
            Map.of("slug", PROBE_SLUG, "name", "Probe Chain", "planCode", "starter"),
            platformAdminToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID chainId = UUID.fromString(response.getBody().get("chainId").asText());
        assertThat(response.getBody().get("schemaName").asText()).isEqualTo("chain_" + PROBE_SLUG);

        // Schema exists and is migrated to the head version of the chain chain.
        Integer headVersion = platformJdbc.queryForObject(
            "SELECT schema_version FROM platform.chain WHERE id = ?", Integer.class, chainId);
        int migrationsOnDisk = countChainMigrations();
        assertThat(headVersion).isEqualTo(migrationsOnDisk);

        // A chain admin logging in sees zero schools.
        String token = jwt.issueAccess(UUID.randomUUID(), chainId.toString(), "chain_" + PROBE_SLUG,
            null, "chain_admin");
        var schools = get("/v1/tenancy/schools", token);
        assertThat(schools.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(schools.getBody()).isEmpty();
    }

    @Test @Tag("P1")
    void cert_TEN_02_twoSchoolsUnderOneChainKeepSeparateStructureAndTheme() {
        var schools = get("/v1/tenancy/schools", chainAdminToken()).getBody();
        assertThat(schools).hasSize(2);

        assertThat(get("/v1/tenancy/schools/" + cbse().id() + "/grades", principalToken(cbse())).getBody())
            .hasSize(cbse().gradeCodes().size());
        assertThat(get("/v1/tenancy/schools/" + cie().id() + "/grades", principalToken(cie())).getBody())
            .hasSize(cie().gradeCodes().size());

        assertThat(get("/v1/tenancy/schools/" + cbse().id() + "/campuses", principalToken(cbse())).getBody())
            .hasSize(2);

        put("/v1/theming/schools/" + cbse().id(), Map.of("primaryColor", "#0b5d1e"), principalToken(cbse()));
        put("/v1/theming/schools/" + cie().id(), Map.of("primaryColor", "#7c2d12"), principalToken(cie()));
        assertThat(get("/v1/theming/schools/" + cbse().id(), principalToken(cbse()))
            .getBody().get("primary_color").asText()).isEqualTo("#0b5d1e");
        assertThat(get("/v1/theming/schools/" + cie().id(), principalToken(cie()))
            .getBody().get("primary_color").asText()).isEqualTo("#7c2d12");
    }

    @Test @Tag("P1")
    void cert_TEN_03_reProvisioningExistingChainIsIdempotent() {
        dropProbeChain();
        String token = platformAdminToken();
        var first = post("/v1/platform-admin/chains",
            Map.of("slug", PROBE_SLUG, "name", "Probe Chain", "planCode", "starter"), token);
        UUID chainId = UUID.fromString(first.getBody().get("chainId").asText());
        Integer versionAfterFirst = platformJdbc.queryForObject(
            "SELECT schema_version FROM platform.chain WHERE id = ?", Integer.class, chainId);

        var second = post("/v1/platform-admin/chains",
            Map.of("slug", PROBE_SLUG, "name", "Probe Chain", "planCode", "starter"), token);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(UUID.fromString(second.getBody().get("chainId").asText())).isEqualTo(chainId);
        assertThat(second.getBody().get("created").asBoolean()).isFalse();

        assertThat(platformJdbc.queryForObject(
            "SELECT count(*) FROM platform.chain WHERE slug = ?", Integer.class, PROBE_SLUG)).isEqualTo(1);
        assertThat(platformJdbc.queryForObject(
            "SELECT schema_version FROM platform.chain WHERE id = ?", Integer.class, chainId))
            .isEqualTo(versionAfterFirst);
    }

    @Test @Tag("P1")
    void cert_TEN_04_curriculumClonedFromTemplateIsIndependentOfTheTemplate() {
        String token = principalToken(cie());
        JsonNode templates = get("/v1/curriculum/templates?boardCode=CIE", token).getBody();
        assertThat(templates).isNotEmpty();
        UUID templateId = UUID.fromString(templates.get(0).get("id").asText());

        var cloned = post("/v1/curriculum/clone-from-template",
            Map.of("schoolId", cie().id(), "templateId", templateId), token);
        assertThat(cloned.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID curriculumId = UUID.fromString(cloned.getBody().get("id").asText());
        assertThat(UUID.fromString(cloned.getBody().get("sourceTemplateId").asText())).isEqualTo(templateId);

        long clonedNodes = count("SELECT count(*) FROM curriculum_node WHERE curriculum_id = ?", curriculumId);
        assertThat(clonedNodes).isGreaterThan(0);

        // Editing the school's copy must not reach back into the platform template.
        String templatePayloadBefore = platformJdbc.queryForObject(
            "SELECT payload::text FROM platform.curriculum_template WHERE id = ?", String.class, templateId);
        UUID nodeId = queryOne("SELECT id FROM curriculum_node WHERE curriculum_id = ? ORDER BY path LIMIT 1",
            UUID.class, curriculumId);
        inChainDo(jdbc -> jdbc.update("UPDATE curriculum_node SET name = 'Renamed by school' WHERE id = ?", nodeId));
        String templatePayloadAfter = platformJdbc.queryForObject(
            "SELECT payload::text FROM platform.curriculum_template WHERE id = ?", String.class, templateId);
        assertThat(templatePayloadAfter).isEqualTo(templatePayloadBefore);
    }

    @Test @Tag("P2")
    void cert_TEN_05_sameSubjectCodeInTwoSchoolsDoesNotCollide() {
        var inCbse = post("/v1/tenancy/schools/" + cbse().id() + "/subjects",
            Map.of("code", "MATH-SHARED", "name", "Mathematics"), principalToken(cbse()));
        var inCie = post("/v1/tenancy/schools/" + cie().id() + "/subjects",
            Map.of("code", "MATH-SHARED", "name", "Mathematics"), principalToken(cie()));

        assertThat(inCbse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inCie.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM subject WHERE code = 'MATH-SHARED'")).isEqualTo(2);
    }

    @Test @Tag("P2")
    void cert_TEN_06_planFeatureFlagGatesModulesWhileDataStaysReadable() {
        String token = principalToken(cbse());
        put("/v1/feature-flags", Map.of("code", "lms", "enabled", false,
            "description", "LMS module", "schoolOverrides", Map.of(), "rolloutPct", 0), token);
        assertThat(get("/v1/feature-flags/lms/enabled", token).getBody().get("enabled").asBoolean()).isFalse();

        put("/v1/feature-flags", Map.of("code", "lms", "enabled", true,
            "description", "LMS module", "schoolOverrides", Map.of(cbse().id().toString(), false),
            "rolloutPct", 100), token);
        assertThat(get("/v1/feature-flags/lms/enabled", token).getBody().get("enabled").asBoolean()).isFalse();
        assertThat(get("/v1/feature-flags/lms/enabled", principalToken(cie()))
            .getBody().get("enabled").asBoolean()).isTrue();

        // Data behind a disabled module stays readable.
        assertThat(get("/v1/tenancy/schools/" + cbse().id() + "/sections", token).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }

    @Test @Tag("P2")
    void cert_TEN_07_multiCampusStructureIsCampusScoped() {
        String token = principalToken(cbse());
        UUID annex = cbse().annexCampusId();
        UUID main = cbse().mainCampusId();

        // A section opened on the annex, and a teacher who works there.
        var annexSection = post("/v1/tenancy/schools/" + cbse().id() + "/sections", Map.of(
            "gradeId", gradeOf(cbse(), cbse().focusGradeCode()), "academicYearId", cbse().currentAy().id(),
            "code", "AX", "name", "Grade 5-AX (Annex)", "strategyCode", cbse().strategyCode(),
            "capacity", 30, "campusId", annex), token);
        assertThat(annexSection.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID annexSectionId = UUID.fromString(annexSection.getBody().get("id").asText());
        assertThat(UUID.fromString(annexSection.getBody().get("campusId").asText())).isEqualTo(annex);

        // Sections created without a campus land on the primary one, so nothing
        // in a single-campus school has to think about this.
        assertThat(queryOne("SELECT campus_id FROM section WHERE id = ?", UUID.class,
            currentFocusSection(cbse()))).isEqualTo(main);

        UUID annexStaffId = UUID.randomUUID();
        UUID annexUserId = UUID.randomUUID();
        try {
            // A timetable slot inherits its section's campus rather than carrying
            // its own answer.
            UUID slotId = UUID.fromString(post("/v1/timetable/slots", Map.of(
                "sectionId", annexSectionId, "subjectId", subjectOf(cbse(), cbse().subjectCodes().get(0)),
                "teacherStaffId", cbse().teacherStaffIds().get(2), "dayOfWeek", 2, "periodNo", 1,
                "startsAt", "09:00:00", "endsAt", "09:45:00", "room", "AX-1",
                "effectiveFrom", "2026-04-01"), token).getBody().get("id").asText());
            assertThat(queryOne("SELECT campus_id FROM timetable_slot WHERE id = ?", UUID.class, slotId))
                .isEqualTo(annex);

            // A campus-level admin: staff on the annex, granted their role over
            // that campus rather than the school.
            inChainDo(jdbc -> {
                jdbc.update(
                    "INSERT INTO staff (id, school_id, campus_id, employee_no, first_name, last_name, " +
                    "  email, employment_type, joined_on) " +
                    "VALUES (?, ?, ?, 'EMP-ANNEX-ADMIN', 'Annex', 'Admin', ?, 'permanent', '2024-04-01')",
                    annexStaffId, cbse().id(), annex, "annex.admin+" + annexStaffId + "@oakridge.test");
                jdbc.update(
                    "INSERT INTO user_account (id, school_id, subject_type, subject_id, email) " +
                    "VALUES (?, ?, 'staff', ?, ?)",
                    annexUserId, cbse().id(), annexStaffId, "annex.admin+" + annexStaffId + "@oakridge.test");
            });
            var granted = post("/v1/iam/staff-roles/assign", Map.of(
                "staffId", annexStaffId, "schoolId", cbse().id(), "roleCode", "vice_principal",
                "scopeType", "campus", "scopeId", annex), token);
            assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            String annexAdminToken = tokenFor(cbse(), annexUserId, "staff");

            // They see their campus's sections and staff, and nothing else's.
            var theirSections = get("/v1/tenancy/schools/" + cbse().id() + "/sections?academicYearId="
                + cbse().currentAy().id(), annexAdminToken);
            assertThat(theirSections.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(theirSections.getBody()).hasSize(1);
            assertThat(UUID.fromString(theirSections.getBody().get(0).get("id").asText()))
                .isEqualTo(annexSectionId);

            var theirStaff = get("/v1/people/staff?schoolId=" + cbse().id(), annexAdminToken);
            assertThat(theirStaff.getBody()).hasSize(1);
            assertThat(UUID.fromString(theirStaff.getBody().get(0).get("id").asText())).isEqualTo(annexStaffId);

            // The school-wide principal still sees everything.
            assertThat(get("/v1/tenancy/schools/" + cbse().id() + "/sections?academicYearId="
                + cbse().currentAy().id(), token).getBody().size()).isGreaterThan(1);

            // And a campus from another school cannot be attached to this one's structure.
            var wrongCampus = post("/v1/tenancy/schools/" + cbse().id() + "/sections", Map.of(
                "gradeId", gradeOf(cbse(), cbse().focusGradeCode()), "academicYearId", cbse().currentAy().id(),
                "code", "XX", "name", "Wrong campus", "strategyCode", cbse().strategyCode(),
                "capacity", 10, "campusId", cie().annexCampusId()), token);
            assertThat(wrongCampus.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM timetable_slot WHERE section_id = ?", annexSectionId);
                jdbc.update("DELETE FROM section WHERE id = ?", annexSectionId);
                jdbc.update("DELETE FROM staff_role WHERE staff_id = ?", annexStaffId);
                jdbc.update("DELETE FROM user_account WHERE id = ?", annexUserId);
                jdbc.update("DELETE FROM staff WHERE id = ?", annexStaffId);
            });
        }
    }

    @Test @Tag("P3")
    void cert_TEN_08_themeChangePropagatesWithoutRedeploy() {
        String token = principalToken(cie());
        put("/v1/theming/schools/" + cie().id(),
            Map.of("primaryColor", "#123456", "parentAppName", "Riverdale Parent"), token);

        var theme = get("/v1/theming/schools/" + cie().id(), token).getBody();
        assertThat(theme.get("primary_color").asText()).isEqualTo("#123456");
        assertThat(theme.get("parent_app_name").asText()).isEqualTo("Riverdale Parent");

        // Public site reads the same school record without an app restart.
        var publicView = get("/v1/public/schools/" + seed.chainSlug() + "/" + cie().slug(), null);
        assertThat(publicView.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- helpers

    private void dropProbeChain() {
        TenantContext.set(TenantContext.platformAdmin(null));
        try {
            platformJdbc.execute("DROP SCHEMA IF EXISTS chain_" + PROBE_SLUG + " CASCADE");
            platformJdbc.update("DELETE FROM platform.chain_schema_version WHERE chain_id IN " +
                "(SELECT id FROM platform.chain WHERE slug = ?)", PROBE_SLUG);
            platformJdbc.update("DELETE FROM platform.chain WHERE slug = ?", PROBE_SLUG);
        } finally {
            TenantContext.clear();
        }
    }

    private int countChainMigrations() {
        try {
            var resources = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/chain/V*.sql");
            return resources.length;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot enumerate chain migrations", e);
        }
    }
}
