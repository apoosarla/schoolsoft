package com.schoolsoft.platform.time;

import com.schoolsoft.platform.tenancy.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * "Today", as the school experiences it.
 *
 * <p>A server on UTC and a school on IST disagree about the date from midnight
 * to 05:30 every morning: an attendance mark made at 01:00 IST on the 16th is
 * the 15th to {@code LocalDate.now()} on that server. Every date the API
 * derives for itself — a default {@code onDate}, a payment's day, the "today"
 * a scope confines by — comes from here, never from the JVM's zone.
 *
 * <p>A caller with no school (a chain admin, a chain-wide job) gets
 * {@link SchoolZones#DEFAULT}. SQL that needs today binds {@link #today}
 * rather than writing {@code CURRENT_DATE}, so a test that pins {@link Clock}
 * moves both sides together.
 */
@Component
public class SchoolClock {

    private final Clock clock;
    private final JdbcTemplate jdbc;

    public SchoolClock(Clock clock, DataSource dataSource) {
        this.clock = clock;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Today at the caller's own school. */
    public LocalDate today() {
        TenantContext.Snapshot snap = TenantContext.get();
        return today(snap == null ? null : snap.schoolId());
    }

    /** Today at {@code schoolId}; the default zone's today when it is null. */
    public LocalDate today(UUID schoolId) {
        return LocalDate.now(clock.withZone(zoneOf(schoolId)));
    }

    public ZoneId zoneOf(UUID schoolId) {
        TenantContext.Snapshot snap = TenantContext.get();
        boolean inChain = snap != null && snap.chainSchema() != null && !"platform".equals(snap.chainSchema());
        if (schoolId == null || !inChain) return SchoolZones.DEFAULT;
        return jdbc.execute((ConnectionCallback<ZoneId>) c -> SchoolZones.of(c, schoolId));
    }
}
