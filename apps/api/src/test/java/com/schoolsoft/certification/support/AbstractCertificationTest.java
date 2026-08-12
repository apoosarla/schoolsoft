package com.schoolsoft.certification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.certification.support.CertificationFixture.SchoolSeed;
import com.schoolsoft.certification.support.CertificationFixture.Seed;
import com.schoolsoft.platform.security.JwtService;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.tenancy.api.ChainProvisioningService;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for every certification scenario.
 *
 * One Spring context and one seeded fixture are shared by the whole suite: the
 * fixture is rebuilt on the first scenario of a JVM run and then read (and
 * written to) by all of them. Scenarios that mutate shared rows are expected to
 * make their own row rather than edit a neighbour's — the fixture is broad
 * enough that they can.
 *
 * Naming: every test method is {@code cert_<AREA>_<NN>_<description>}, and
 * {@link com.schoolsoft.certification.CatalogueSyncTest} enforces that the set
 * of ids here matches {@code docs/certification-test-scenarios.md} exactly.
 * A scenario blocked by a product gap is {@code @Disabled("GAP-nn — …")}; the
 * disabled list is the remaining work.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("cert")
public abstract class AbstractCertificationTest {

    private static Seed sharedSeed;

    protected Seed seed;

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JwtService jwt;
    @Autowired protected DataSource dataSource;
    @Autowired protected JdbcTemplate platformJdbc;
    @Autowired protected ChainProvisioningService provisioning;

    protected final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CertDatabase::url);
        registry.add("spring.datasource.username", CertDatabase::username);
        registry.add("spring.datasource.password", CertDatabase::password);
        // The fixture provisions its chain explicitly; startup auto-migration
        // would race the drop-and-rebuild.
        registry.add("schoolsoft.chain-migrations.auto-apply-on-startup", () -> "false");
        // Keep the outbox drainer out of the scenarios' way.
        registry.add("schoolsoft.jobs.outbox.delay-ms", () -> "3600000");
        registry.add("schoolsoft.jwt.secret", () -> "certification-suite-secret-certification-suite-secret");
    }

    @BeforeEach
    void ensureFixture() {
        if (sharedSeed == null) {
            sharedSeed = new CertificationFixture(dataSource, platformJdbc, provisioning).rebuild();
        }
        seed = sharedSeed;
    }

    protected SchoolSeed cbse() { return seed.cbse(); }

    protected SchoolSeed cie() { return seed.cie(); }

    // ----------------------------------------------------------------- tokens

    protected String tokenFor(SchoolSeed school, UUID userAccountId, String subjectType) {
        return jwt.issueAccess(userAccountId, seed.chainId().toString(), seed.chainSchema(),
            school == null ? null : school.id(), subjectType);
    }

    protected String principalToken(SchoolSeed school) {
        return tokenFor(school, school.principalUserId(), "staff");
    }

    protected String accountantToken(SchoolSeed school) {
        return tokenFor(school, school.accountantUserId(), "staff");
    }

    protected String registrarToken(SchoolSeed school) {
        return tokenFor(school, school.registrarUserId(), "staff");
    }

    protected String librarianToken(SchoolSeed school) {
        return tokenFor(school, school.librarianUserId(), "staff");
    }

    /** Teacher 1 is the class teacher of every focus section. */
    protected String teacherToken(SchoolSeed school, int index) {
        return tokenFor(school, school.teacherUserIds().get(index), "staff");
    }

    protected String guardianTokenFor(SchoolSeed school, UUID studentId) {
        UUID userId = queryOne(
            "SELECT ua.id FROM user_account ua " +
            "JOIN guardian_student gs ON gs.guardian_id = ua.subject_id AND ua.subject_type = 'guardian' " +
            "WHERE gs.student_id = ? ORDER BY gs.is_primary DESC LIMIT 1", UUID.class, studentId);
        return tokenFor(school, userId, "guardian");
    }

    protected String chainAdminToken() {
        return jwt.issueAccess(seed.chainAdminUserId(), seed.chainId().toString(), seed.chainSchema(),
            null, "chain_admin");
    }

    protected String platformAdminToken() {
        UUID platformUserId = platformJdbc.queryForObject(
            "SELECT id FROM platform.platform_user WHERE email = 'admin@schoolsoft.dev'", UUID.class);
        return jwt.issueAccess(platformUserId, "platform", "platform", null, "platform_admin");
    }

    // ------------------------------------------------------------------- http

    protected ResponseEntity<JsonNode> get(String path, String token) {
        return exchange(HttpMethod.GET, path, null, token);
    }

    protected ResponseEntity<JsonNode> post(String path, Object body, String token) {
        return exchange(HttpMethod.POST, path, body, token);
    }

    protected ResponseEntity<JsonNode> put(String path, Object body, String token) {
        return exchange(HttpMethod.PUT, path, body, token);
    }

    protected ResponseEntity<JsonNode> delete(String path, String token) {
        return exchange(HttpMethod.DELETE, path, null, token);
    }

    protected ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        String payload;
        try {
            payload = body == null ? null : json.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unserialisable request body", e);
        }
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), JsonNode.class);
    }

    /** Request body builder for payloads wider than Map.of's ten-pair ceiling. */
    protected static java.util.Map<String, Object> body(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("body() takes alternating keys and values");
        }
        var map = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    // --------------------------------------------------------------- database

    /**
     * Runs a query against the chain schema in a trusted context — used for
     * asserting what an endpoint actually wrote, not for setting up state a
     * scenario should create through the API.
     */
    protected <T> T inChain(Function<JdbcTemplate, T> body) {
        TenantContext.set(TenantContext.trustedJob(seed.chainSchema(), seed.chainId()));
        try {
            return body.apply(new JdbcTemplate(dataSource));
        } finally {
            TenantContext.clear();
        }
    }

    protected void inChainDo(java.util.function.Consumer<JdbcTemplate> body) {
        inChain(jdbc -> { body.accept(jdbc); return null; });
    }

    protected <T> T queryOne(String sql, Class<T> type, Object... args) {
        return inChain(jdbc -> jdbc.queryForObject(sql, type, args));
    }

    protected <T> List<T> queryList(String sql, Class<T> type, Object... args) {
        return inChain(jdbc -> jdbc.queryForList(sql, type, args));
    }

    protected long count(String sql, Object... args) {
        Long n = queryOne(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    // -------------------------------------------------------- fixture lookups

    protected UUID sectionOf(SchoolSeed school, String ayCode, String gradeCode, String sectionCode) {
        return CertificationFixture.sectionId(school.slug(), ayCode, gradeCode, sectionCode);
    }

    protected UUID currentFocusSection(SchoolSeed school) {
        return sectionOf(school, school.currentAy().code(), school.focusGradeCode(), school.focusSectionCode());
    }

    protected UUID priorFocusSection(SchoolSeed school) {
        return sectionOf(school, school.priorAy().code(), school.focusGradeCode(), school.focusSectionCode());
    }

    protected UUID gradeOf(SchoolSeed school, String gradeCode) {
        return CertificationFixture.id(school.slug() + ":grade:" + gradeCode);
    }

    protected UUID subjectOf(SchoolSeed school, String subjectCode) {
        return CertificationFixture.id(school.slug() + ":subject:" + subjectCode);
    }

    protected UUID termOf(SchoolSeed school, String ayCode, String termCode) {
        return CertificationFixture.id(school.slug() + ":term:" + ayCode + ":" + termCode);
    }

    protected List<UUID> studentsIn(UUID sectionId) {
        return queryList("SELECT student_id FROM enrolment WHERE section_id = ? ORDER BY roll_no",
            UUID.class, sectionId);
    }

    protected UUID firstStudentIn(UUID sectionId) {
        return studentsIn(sectionId).get(0);
    }
}
