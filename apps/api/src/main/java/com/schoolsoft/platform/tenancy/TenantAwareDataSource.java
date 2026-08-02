package com.schoolsoft.platform.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Wraps the real {@link DataSource} so that every checked-out connection is
 * pinned to the current tenant's schema via {@code SET LOCAL search_path}
 * inside a transaction, and via session-level SET when no tx is open.
 *
 * Per design §5a "PgBouncer + SET search_path gotchas (R13)" — always SET
 * LOCAL inside a transaction. We also set {@code app.school_id} and
 * {@code app.trusted} so the RLS policies in V009 can enforce isolation.
 *
 * NOTE: When no {@link TenantContext} is present we fall back to the platform
 * schema. That is the case for Flyway migrations of the platform schema and
 * for actuator endpoints.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private final String defaultSchema;

    public TenantAwareDataSource(DataSource target, String defaultSchema) {
        super(target);
        this.defaultSchema = defaultSchema;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return decorate(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return decorate(super.getConnection(username, password));
    }

    private Connection decorate(Connection raw) throws SQLException {
        TenantContext.Snapshot snap = TenantContext.get();
        String schema = (snap == null || snap.chainSchema() == null) ? defaultSchema : snap.chainSchema();
        boolean trusted = snap != null && snap.trusted();
        String schoolId = (snap == null || snap.schoolId() == null) ? null : snap.schoolId().toString();

        try (Statement st = raw.createStatement()) {
            st.execute("SET search_path TO " + safeIdent(schema) + ", platform");
            st.execute("SET app.trusted TO " + (trusted ? "'true'" : "'false'"));
            if (schoolId != null) {
                st.execute("SET app.school_id TO '" + schoolId + "'");
            } else {
                st.execute("RESET app.school_id");
            }
        }
        return raw;
    }

    private static String safeIdent(String s) {
        if (!s.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("Unsafe schema identifier: " + s);
        }
        return s;
    }
}
