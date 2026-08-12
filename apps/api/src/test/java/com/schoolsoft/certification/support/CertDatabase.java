package com.schoolsoft.certification.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The Postgres instance the certification suite runs against.
 *
 * Default is a Testcontainers Postgres started once per JVM and reused by
 * every scenario class. Where a Docker daemon is not available (some dev
 * machines, some CI runners) an existing server can be pointed at instead:
 *
 * <pre>
 *   SCHOOLSOFT_TEST_DB_URL=jdbc:postgresql://localhost:5432/schoolsoft_cert \
 *   SCHOOLSOFT_TEST_DB_USER=schoolsoft SCHOOLSOFT_TEST_DB_PASSWORD=schoolsoft \
 *   ./mvnw test
 * </pre>
 *
 * The suite drops and re-provisions its own chain schema on every run
 * ({@link CertificationFixture}), so an external server is safe as long as the
 * database is dedicated to the suite.
 */
public final class CertDatabase {

    private static final String IMAGE = "postgres:16-alpine";

    private static PostgreSQLContainer<?> container;
    private static String url;
    private static String username;
    private static String password;

    private CertDatabase() {}

    public static synchronized void start() {
        if (url != null) return;

        String externalUrl = setting("SCHOOLSOFT_TEST_DB_URL", "schoolsoft.test.db.url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            url = externalUrl;
            username = orDefault(setting("SCHOOLSOFT_TEST_DB_USER", "schoolsoft.test.db.user"), "schoolsoft");
            password = orDefault(setting("SCHOOLSOFT_TEST_DB_PASSWORD", "schoolsoft.test.db.password"), "schoolsoft");
            return;
        }

        container = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE))
                .withDatabaseName("schoolsoft_cert")
                .withUsername("schoolsoft")
                .withPassword("schoolsoft");
        container.start();
        url = container.getJdbcUrl();
        username = container.getUsername();
        password = container.getPassword();
    }

    public static String url() { start(); return url; }

    public static String username() { start(); return username; }

    public static String password() { start(); return password; }

    private static String setting(String env, String property) {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null && !fromProperty.isBlank()) return fromProperty;
        return System.getenv(env);
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
