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
                "scopeType", "campus", "scopeId", annex,
                "reason", "Campus-level admin for the annex"), token);
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


    /**
     * The operator's door into a chain they do not belong to. A
     * platform-admin token carries the platform schema, so every one of these
     * reads steps into the chain as a trusted job — which is also why they are
     * platform-admin only.
     */
    @Test @Tag("P2")
    void cert_TEN_14_platformConsoleListsAndOpensSchoolsInsideAChain() {
        String token = platformAdminToken();
        String base = "/v1/platform-admin/chains/" + seed.chainId();

        var before = get(base + "/schools", token);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(before.getBody()).hasSize(2);

        var created = post(base + "/schools", Map.of(
            "slug", "cert-probe-platform", "name", "Probe School (platform)",
            "boardCode", "CBSE", "stateCode", "KA"), token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID schoolId = UUID.fromString(created.getBody().get("id").asText());
        try {
            assertThat(created.getBody().get("lifecycle").asText()).isEqualTo("draft");

            // It landed in this chain's schema, not the platform one.
            assertThat(count("SELECT count(*) FROM school WHERE id = ?", schoolId)).isEqualTo(1);

            var listed = get(base + "/schools", token).getBody();
            assertThat(listed).hasSize(3);
            var probe = java.util.stream.StreamSupport.stream(listed.spliterator(), false)
                .filter(s -> s.get("id").asText().equals(schoolId.toString()))
                .findFirst().orElseThrow();
            assertThat(probe.get("lifecycle").asText()).isEqualTo("draft");
            assertThat(probe.get("activeEnrolments").asLong()).isZero();

            // And its checklist reads from outside the chain.
            var readiness = get(base + "/schools/" + schoolId + "/readiness", token);
            assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(readiness.getBody().get("canGoLive").asBoolean()).isFalse();
            assertThat(readiness.getBody().get("steps")).hasSize(10);
        } finally {
            deleteProbeSchool(schoolId);
        }
    }

    // ------------------------------------------------- opening a school

    @Test @Tag("P1")
    void cert_TEN_09_newSchoolStartsInDraftAndNamesWhatIsLeftToDo() {
        UUID schoolId = createProbeSchool("cert-probe-draft");
        try {
            var school = get("/v1/tenancy/schools/" + schoolId, chainAdminToken()).getBody();
            assertThat(school.get("lifecycle").asText()).isEqualTo("draft");
            // The API omits a null field, so "never opened" is the absence of
            // the moment rather than a null one.
            assertThat(school.hasNonNull("wentLiveAt")).isFalse();

            var readiness = get("/v1/tenancy/schools/" + schoolId + "/readiness", chainAdminToken());
            assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(readiness.getBody().get("canGoLive").asBoolean()).isFalse();

            var steps = readiness.getBody().get("steps");
            var blockingOpen = new java.util.ArrayList<String>();
            steps.forEach(step -> {
                assertThat(step.get("done").asBoolean()).isFalse();
                assertThat(step.get("why").asText()).isNotBlank();
                if (step.get("blocking").asBoolean()) blockingOpen.add(step.get("key").asText());
            });
            assertThat(blockingOpen).containsExactly(
                "campus", "academic_year", "terms", "grades", "sections", "subjects", "admin_account");

            // Derived, not stored: give the school a campus and the same
            // question answers differently with nothing else written.
            inChainDo(jdbc -> jdbc.update(
                "INSERT INTO campus (school_id, name, is_primary) VALUES (?, 'Main Campus', TRUE)", schoolId));
            var after = get("/v1/tenancy/schools/" + schoolId + "/readiness", chainAdminToken()).getBody();
            assertThat(stepOf(after, "campus").get("done").asBoolean()).isTrue();
            assertThat(stepOf(after, "campus").get("count").asLong()).isEqualTo(1);
            assertThat(after.get("canGoLive").asBoolean()).isFalse();
        } finally {
            deleteProbeSchool(schoolId);
        }
    }

    @Test @Tag("P1")
    void cert_TEN_10_goingLiveIsRefusedWhileABlockingStepIsOpen() {
        UUID schoolId = createProbeSchool("cert-probe-refused");
        try {
            fillBlockingSteps(schoolId, false);   // everything but the sections

            var refused = post("/v1/tenancy/schools/" + schoolId + "/go-live", null, chainAdminToken());
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody().get("message").asText()).contains("Sections");

            // Refused means unchanged, not half-opened.
            assertThat(queryOne("SELECT lifecycle FROM school WHERE id = ?", String.class, schoolId))
                .isEqualTo("draft");
        } finally {
            deleteProbeSchool(schoolId);
        }
    }

    @Test @Tag("P1")
    void cert_TEN_11_goingLiveTwiceIsNotAnError() {
        UUID schoolId = createProbeSchool("cert-probe-live");
        try {
            fillBlockingSteps(schoolId, true);

            var opened = post("/v1/tenancy/schools/" + schoolId + "/go-live", null, chainAdminToken());
            assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(opened.getBody().get("lifecycle").asText()).isEqualTo("live");
            assertThat(opened.getBody().get("canGoLive").asBoolean()).isTrue();
            String wentLiveAt = opened.getBody().get("wentLiveAt").asText();
            assertThat(wentLiveAt).isNotBlank();

            // The retry a dropped response would produce.
            var again = post("/v1/tenancy/schools/" + schoolId + "/go-live", null, chainAdminToken());
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(again.getBody().get("lifecycle").asText()).isEqualTo("live");
            assertThat(again.getBody().get("wentLiveAt").asText()).isEqualTo(wentLiveAt);
        } finally {
            deleteProbeSchool(schoolId);
        }
    }

    @Test @Tag("P2")
    void cert_TEN_12_anOptionalStepIsSkippedWithAReasonAndABlockingOneCannotBe() {
        UUID schoolId = createProbeSchool("cert-probe-skip");
        try {
            String token = chainAdminToken();

            var skipped = post("/v1/tenancy/schools/" + schoolId + "/steps/fee_structure/skip",
                Map.of("reason", "the chain bills centrally this year"), token);
            assertThat(skipped.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(stepOf(skipped.getBody(), "fee_structure").get("skipped").asBoolean()).isTrue();
            assertThat(stepOf(skipped.getBody(), "fee_structure").get("skipReason").asText())
                .isEqualTo("the chain bills centrally this year");

            assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'school.setup_step_skipped' "
                + "AND target_id = ?", schoolId)).isEqualTo(1);

            // A skip with no reason is refused by the audit interceptor, ahead
            // of the handler, and nothing is written.
            var noReason = post("/v1/tenancy/schools/" + schoolId + "/steps/working_week/skip",
                Map.of(), token);
            assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

            // A blocking step is not skippable at any price.
            var refused = post("/v1/tenancy/schools/" + schoolId + "/steps/sections/skip",
                Map.of("reason", "we will do it later"), token);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(count("SELECT count(*) FROM school_onboarding_skip WHERE school_id = ? "
                + "AND step_key = 'sections'", schoolId)).isZero();

            var restored = post("/v1/tenancy/schools/" + schoolId + "/steps/fee_structure/unskip", null, token);
            assertThat(stepOf(restored.getBody(), "fee_structure").get("skipped").asBoolean()).isFalse();
        } finally {
            deleteProbeSchool(schoolId);
        }
    }

    /**
     * The chain admin's single write. Opening a school is theirs so a chain
     * does not raise a ticket with Schoolsoft to open its own; everything
     * inside the school stays with the people who work there.
     */
    @Test @Tag("P1")
    void cert_TEN_13_chainAdminOpensASchoolAndCanChangeNothingInsideIt() {
        UUID schoolId = createProbeSchool("cert-probe-hq");
        try {
            String token = chainAdminToken();
            fillBlockingSteps(schoolId, true);
            assertThat(post("/v1/tenancy/schools/" + schoolId + "/go-live", null, token).getStatusCode())
                .isEqualTo(HttpStatus.OK);

            // And nothing else in the school is theirs to change.
            assertThat(post("/v1/tenancy/schools/" + schoolId + "/grades",
                Map.of("code", "HQ1", "name", "Grade HQ", "sortOrder", 1), token).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(post("/v1/tenancy/schools/" + schoolId + "/subjects",
                Map.of("code", "HQ-MATH", "name", "Mathematics"), token).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            deleteProbeSchool(schoolId);
        }
    }

    /**
     * The handover. A school opened by its chain has nobody in it, so the
     * step that asks for "somebody who can run the school" was one no screen
     * could tick — every environment ticked it with hand-written SQL. This is
     * that act through the front door: the chain appoints one person, that
     * person signs in while the school is still draft and starts building it,
     * and the door shuts behind them.
     */
    @Test @Tag("P1")
    void cert_TEN_16_chainAppointsTheFirstAdministratorAndTheDoorShutsBehindThem() {
        UUID schoolId = createProbeSchool("cert-probe-keys");
        String headEmail = "probe.keys+" + schoolId + "@oakridge.test";
        try {
            String hq = chainAdminToken();
            var before = get("/v1/tenancy/schools/" + schoolId + "/readiness", hq);
            assertThat(stepOf(before.getBody(), "admin_account").get("done").asBoolean()).isFalse();
            assertThat(stepOf(before.getBody(), "campus").get("done").asBoolean()).isFalse();

            var handed = post("/v1/tenancy/schools/" + schoolId + "/first-admin", Map.of(
                "firstName", "Probe", "lastName", "Keyholder",
                "email", headEmail, "roleCode", "principal"), hq);
            assertThat(handed.getStatusCode()).isEqualTo(HttpStatus.OK);

            // A staff row needs a campus, so the first campus comes with the
            // first keyholder — two steps of the checklist, one act.
            assertThat(handed.getBody().get("campusCreated").asBoolean()).isTrue();
            assertThat(stepOf(handed.getBody().get("readiness"), "admin_account").get("done").asBoolean())
                .isTrue();
            assertThat(stepOf(handed.getBody().get("readiness"), "campus").get("done").asBoolean()).isTrue();

            // The point of appointing them: they can set the school up, while
            // it is still draft, with nobody else involved.
            var signedIn = signIn(headEmail);
            assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
            String head = signedIn.getBody().get("accessToken").asText();
            assertThat(post("/v1/tenancy/schools/" + schoolId + "/grades",
                Map.of("code", "1", "name", "Grade 1", "sortOrder", 1), head).getStatusCode())
                .isEqualTo(HttpStatus.OK);

            // And the door shuts: the chain appoints one person, not a staff list.
            var second = post("/v1/tenancy/schools/" + schoolId + "/first-admin", Map.of(
                "firstName", "Second", "email", "probe.second+" + schoolId + "@oakridge.test"), hq);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(count("SELECT count(*) FROM staff WHERE school_id = ?", schoolId)).isEqualTo(1);
        } finally {
            deleteProbeSchool(schoolId);
        }
    }

    /**
     * The handover one level up, and the act that used to be hand-written SQL
     * in every environment. A chain Schoolsoft has just provisioned is empty:
     * no schools, and — the part that mattered — no accounts, so nobody can
     * sign in to it at all. Appointing a school's first keyholder is the chain
     * admin's job (TEN-16), which is unreachable while the chain has no chain
     * admin, so a chain the operator could not hand over was a chain whose
     * schools could never leave {@code draft}.
     *
     * <p>The assertion that matters is the chain of custody: the operator
     * appoints one person and stops, that person signs in through the ordinary
     * door, and everything after it happens inside the customer's chain
     * without Schoolsoft.</p>
     */
    @Test @Tag("P1")
    void cert_TEN_17_operatorHandsAProvisionedChainToItsCustomerOnce() {
        dropProbeChain();
        String operator = platformAdminToken();
        var provisioned = post("/v1/platform-admin/chains",
            Map.of("slug", PROBE_SLUG, "name", "Probe Chain", "planCode", "starter"), operator);
        UUID chainId = UUID.fromString(provisioned.getBody().get("chainId").asText());
        String hqEmail = "probe.hq@" + PROBE_SLUG + ".test";
        try {
            // Provisioned and nobody's: the state the console has to be able to see.
            assertThat(get("/v1/platform-admin/chains/" + chainId + "/admins", operator).getBody()).isEmpty();

            var appointed = post("/v1/platform-admin/chains/" + chainId + "/admins",
                Map.of("email", hqEmail), operator);
            assertThat(appointed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(appointed.getBody().get("signsInWith").asText()).isEqualTo(hqEmail);

            // Through the ordinary door, not a token the test minted itself.
            var signedIn = signInTo(PROBE_SLUG, hqEmail);
            assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
            String hq = signedIn.getBody().get("accessToken").asText();

            // And they can do the chain admin's whole job with no operator
            // involved: open a school, and appoint the person who will run it.
            var school = post("/v1/tenancy/schools",
                Map.of("slug", "probe-handover", "name", "Probe Handover School", "boardCode", "CBSE"), hq);
            assertThat(school.getStatusCode()).isEqualTo(HttpStatus.OK);
            UUID schoolId = UUID.fromString(school.getBody().get("id").asText());

            var handed = post("/v1/tenancy/schools/" + schoolId + "/first-admin", Map.of(
                "firstName", "Probe", "lastName", "Keyholder",
                "email", "probe.head@" + PROBE_SLUG + ".test", "roleCode", "principal"), hq);
            assertThat(handed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(stepOf(handed.getBody().get("readiness"), "admin_account").get("done").asBoolean())
                .isTrue();

            // Recorded where the customer can read it, naming the operator who
            // put an account they did not create into their chain.
            var trail = inProbeChain(jdbc -> jdbc.queryForList(
                "SELECT reason FROM audit_log WHERE action = 'chain.admin_appointed'", String.class));
            assertThat(trail).hasSize(1);
            assertThat(trail.get(0)).contains("admin@schoolsoft.dev");

            // The door shuts: a chain is handed over once, and the second HQ
            // account is the customer's own business.
            var second = post("/v1/platform-admin/chains/" + chainId + "/admins",
                Map.of("email", "probe.hq2@" + PROBE_SLUG + ".test"), operator);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(get("/v1/platform-admin/chains/" + chainId + "/admins", operator).getBody()).hasSize(1);
        } finally {
            dropProbeChain();
        }
    }

    /**
     * What TEN-17's closed door left the customer to do themselves. A chain
     * handed over to one address was a chain one departure away from needing
     * Schoolsoft to run SQL; the HQ now grows and shrinks its own membership,
     * and the one thing it cannot do is shrink it to nobody.
     */
    @Test @Tag("P1")
    void cert_TEN_18_hqAddsASecondAdminAndCannotRemoveTheLast() {
        dropProbeChain();
        String operator = platformAdminToken();
        var provisioned = post("/v1/platform-admin/chains",
            Map.of("slug", PROBE_SLUG, "name", "Probe Chain", "planCode", "starter"), operator);
        UUID chainId = UUID.fromString(provisioned.getBody().get("chainId").asText());
        String firstEmail = "probe.hq@" + PROBE_SLUG + ".test";
        String secondEmail = "probe.hq2@" + PROBE_SLUG + ".test";
        try {
            UUID firstId = UUID.fromString(post("/v1/platform-admin/chains/" + chainId + "/admins",
                Map.of("email", firstEmail), operator).getBody().get("accountId").asText());
            String first = signInTo(PROBE_SLUG, firstEmail).getBody().get("accessToken").asText();

            // The HQ adds its own second account, with no operator involved.
            var added = post("/v1/tenancy/chain/admins", Map.of("email", secondEmail), first);
            assertThat(added.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(added.getBody().get("active").asBoolean()).isTrue();
            assertThat(get("/v1/tenancy/chain/admins", first).getBody()).hasSize(2);

            // An address already in the chain is refused, not given a second door.
            assertThat(post("/v1/tenancy/chain/admins", Map.of("email", secondEmail), first).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

            // The newcomer signs in through the ordinary door and removes the
            // first — the departure this exists for. The reason is required.
            var signedIn = signInTo(PROBE_SLUG, secondEmail);
            assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
            String second = signedIn.getBody().get("accessToken").asText();
            UUID secondId = UUID.fromString(added.getBody().get("accountId").asText());

            assertThat(post("/v1/tenancy/chain/admins/" + firstId + "/deactivate", Map.of(), second)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            var removed = post("/v1/tenancy/chain/admins/" + firstId + "/deactivate",
                Map.of("reason", "certification: left the company"), second);
            assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(removed.getBody().get("active").asBoolean()).isFalse();

            // A retry after a dropped response is not an error.
            assertThat(post("/v1/tenancy/chain/admins/" + firstId + "/deactivate",
                Map.of("reason", "certification: retry"), second).getStatusCode()).isEqualTo(HttpStatus.OK);

            // Gone means gone: the first can no longer sign in.
            assertThat(signInTo(PROBE_SLUG, firstEmail).getStatusCode()).isNotEqualTo(HttpStatus.OK);

            // And the chain is never left with nobody.
            var last = post("/v1/tenancy/chain/admins/" + secondId + "/deactivate",
                Map.of("reason", "certification: trying to leave nobody"), second);
            assertThat(last.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(this.<Integer>inProbeChain(jdbc -> jdbc.queryForObject(
                "SELECT count(*) FROM user_account WHERE subject_type = 'chain_admin' AND is_active",
                Integer.class))).isEqualTo(1);

            // Both acts are in the customer's audit log, the removal with its reason.
            assertThat(this.<Integer>inProbeChain(jdbc -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'chain.admin_added' AND target_id = ?",
                Integer.class, secondId))).isEqualTo(1);
            assertThat(this.<java.util.List<String>>inProbeChain(jdbc -> jdbc.queryForList(
                "SELECT reason FROM audit_log WHERE action = 'chain.admin_deactivated' AND target_id = ?",
                String.class, firstId))).contains("certification: left the company");

            // A school's staff cannot reach it, even when a custom role names
            // the permission: an HQ account sees every school in the chain, so
            // a school's employee must never be able to mint one.
            var school = post("/v1/tenancy/schools",
                Map.of("slug", "probe-hq", "name", "Probe HQ School", "boardCode", "CBSE"), second);
            UUID schoolId = UUID.fromString(school.getBody().get("id").asText());
            String headEmail = "probe.head@" + PROBE_SLUG + ".test";
            assertThat(post("/v1/tenancy/schools/" + schoolId + "/first-admin", Map.of(
                "firstName", "Probe", "lastName", "Head", "email", headEmail, "roleCode", "principal"),
                second).getStatusCode()).isEqualTo(HttpStatus.OK);
            inProbeChain(jdbc -> jdbc.update(
                "INSERT INTO role_perm (role_code, perm_code) VALUES ('principal', 'chain.admin.manage')"));
            String head = signInTo(PROBE_SLUG, headEmail).getBody().get("accessToken").asText();

            assertThat(post("/v1/tenancy/chain/admins",
                Map.of("email", "probe.hq3@" + PROBE_SLUG + ".test"), head).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get("/v1/tenancy/chain/admins", head).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(this.<Integer>inProbeChain(jdbc -> jdbc.queryForObject(
                "SELECT count(*) FROM user_account WHERE email = ?", Integer.class,
                "probe.hq3@" + PROBE_SLUG + ".test"))).isZero();
        } finally {
            dropProbeChain();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Signs in against a chain other than the fixture's — a probe chain has its own slug. */
    private org.springframework.http.ResponseEntity<JsonNode> signInTo(String chainSlug, String identifier) {
        post("/v1/auth/otp/start", Map.of("identifier", identifier, "chainSlug", chainSlug), null);
        return post("/v1/auth/otp/verify",
            Map.of("identifier", identifier, "chainSlug", chainSlug, "code", "000000"), null);
    }

    /** Reads the probe chain's own tables, the way {@code inChain} reads the fixture's. */
    private <T> T inProbeChain(java.util.function.Function<org.springframework.jdbc.core.JdbcTemplate, T> body) {
        TenantContext.set(TenantContext.trustedJob("chain_" + PROBE_SLUG, null));
        try {
            return body.apply(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
        } finally {
            TenantContext.clear();
        }
    }

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

    /**
     * What {@code draft} is for. A half-built school shows a parent an empty
     * timetable, an empty ledger and a child on no register, and none of that
     * says "not yet" — so until somebody opens the school, the door is the
     * office's alone.
     */
    @Test @Tag("P1")
    void cert_TEN_15_aFamilyCannotSignInUntilTheSchoolOpens() {
        UUID schoolId = createProbeSchool("cert-probe-door");
        String guardianEmail = "probe.family+" + schoolId + "@oakridge.test";
        try {
            inChainDo(jdbc -> jdbc.update(
                "INSERT INTO user_account (school_id, subject_type, subject_id, email) " +
                "VALUES (?, 'guardian', NULL, ?)", schoolId, guardianEmail));

            var refused = signIn(guardianEmail);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refused.getBody().get("message").asText()).contains("has not opened yet");

            // The office gets in while the school is still being built —
            // somebody has to build it.
            fillBlockingSteps(schoolId, true);
            String staffEmail = queryOne(
                "SELECT email FROM user_account WHERE school_id = ? AND subject_type = 'staff'",
                String.class, schoolId);
            assertThat(signIn(staffEmail).getStatusCode()).isEqualTo(HttpStatus.OK);

            // And the family's door opens with the school's.
            assertThat(post("/v1/tenancy/schools/" + schoolId + "/go-live", null, chainAdminToken())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
            var admitted = signIn(guardianEmail);
            assertThat(admitted.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(admitted.getBody().get("accessToken").asText()).isNotBlank();
        } finally {
            inChainDo(jdbc -> jdbc.update("DELETE FROM user_account WHERE email = ?", guardianEmail));
            deleteProbeSchool(schoolId);
        }
    }

    // ------------------------------------------------ the probe school

    /** The OTP front door, with the dev bypass code the suite's profile leaves on. */
    private org.springframework.http.ResponseEntity<JsonNode> signIn(String identifier) {
        post("/v1/auth/otp/start", Map.of("identifier", identifier, "chainSlug", seed.chainSlug()), null);
        return post("/v1/auth/otp/verify",
            Map.of("identifier", identifier, "chainSlug", seed.chainSlug(), "code", "000000"), null);
    }


    private JsonNode stepOf(JsonNode readiness, String key) {
        for (JsonNode step : readiness.get("steps")) {
            if (step.get("key").asText().equals(key)) return step;
        }
        throw new AssertionError("No setup step '" + key + "' in the readiness answer");
    }

    /** Opened by the chain's own HQ admin, which is the point of TEN-13. */
    private UUID createProbeSchool(String slug) {
        var created = post("/v1/tenancy/schools",
            Map.of("slug", slug, "name", "Probe School", "boardCode", "CBSE", "stateCode", "KA"),
            chainAdminToken());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }

    /**
     * Fills the blocking steps by writing the rows they ask about — the
     * scenario is about readiness, not about the structure endpoints, which
     * ACAD covers. {@code withSections} false leaves exactly one step open.
     */
    private void fillBlockingSteps(UUID schoolId, boolean withSections) {
        inChainDo(jdbc -> {
            jdbc.update("INSERT INTO campus (school_id, name, is_primary) VALUES (?, 'Main Campus', TRUE)",
                schoolId);
            jdbc.update("INSERT INTO academic_year (school_id, code, starts_on, ends_on, is_current) " +
                "VALUES (?, '2026-27', '2026-06-01', '2027-03-31', TRUE)", schoolId);
            jdbc.update("INSERT INTO term (academic_year_id, code, name, starts_on, ends_on) " +
                "SELECT id, 'T1', 'Term 1', '2026-06-01', '2026-09-30' FROM academic_year " +
                "WHERE school_id = ? AND is_current", schoolId);
            jdbc.update("INSERT INTO grade (school_id, code, name, sort_order) VALUES (?, '1', 'Grade 1', 1)",
                schoolId);
            jdbc.update("INSERT INTO subject (school_id, code, name) VALUES (?, 'MATH', 'Mathematics')",
                schoolId);
            if (withSections) {
                // campus_id is left to the trigger, which lands it on the
                // school's primary campus.
                jdbc.update("INSERT INTO section (school_id, grade_id, academic_year_id, code, name, " +
                    "  strategy_code, capacity) " +
                    "SELECT ?, g.id, ay.id, 'A', 'Grade 1-A', 'CBSE-CCE-2024', 40 " +
                    "FROM grade g, academic_year ay " +
                    "WHERE g.school_id = ? AND g.code = '1' AND ay.school_id = ? AND ay.is_current",
                    schoolId, schoolId, schoolId);
            }
            // Somebody who can run the place: a staff member with an account
            // and a role that holds structure.manage.
            UUID staffId = UUID.randomUUID();
            jdbc.update("INSERT INTO staff (id, school_id, employee_no, first_name, last_name, email, " +
                "  employment_type, joined_on) " +
                "VALUES (?, ?, 'EMP-PROBE-HEAD', 'Probe', 'Head', ?, 'permanent', '2026-05-01')",
                staffId, schoolId, "probe.head+" + staffId + "@oakridge.test");
            jdbc.update("INSERT INTO user_account (id, school_id, subject_type, subject_id, email) " +
                "VALUES (?, ?, 'staff', ?, ?)",
                UUID.randomUUID(), schoolId, staffId, "probe.head+" + staffId + "@oakridge.test");
            jdbc.update("INSERT INTO staff_role (staff_id, role_code, scope_type, scope_id) " +
                "VALUES (?, 'principal', 'school', ?)", staffId, schoolId);
        });
    }

    /**
     * Takes the probe school back out of the chain so the scenarios that count
     * this chain's schools still count two. The audit rows it wrote stay where
     * they are — deleting a subset of {@code audit_log} forks the hash chain,
     * which is the thing the chain exists to expose.
     */
    private void deleteProbeSchool(UUID schoolId) {
        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM school_onboarding_skip WHERE school_id = ?", schoolId);
            jdbc.update("DELETE FROM staff_role WHERE staff_id IN (SELECT id FROM staff WHERE school_id = ?)",
                schoolId);
            jdbc.update("DELETE FROM user_account WHERE school_id = ?", schoolId);
            jdbc.update("DELETE FROM staff WHERE school_id = ?", schoolId);
            // section / subject / term / academic_year / grade / campus cascade
            // from the school row itself.
            jdbc.update("DELETE FROM school WHERE id = ?", schoolId);
        });
    }

}
