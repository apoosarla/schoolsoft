package com.schoolsoft.platform.time;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The zone a school keeps its days in — {@code school.timezone}, read once per
 * school and remembered.
 *
 * <p>Shared by the two places that need it: {@link SchoolClock}, which answers
 * "what is today?" in Java, and the tenant datasource, which pins every
 * connection's Postgres {@code TimeZone} so that {@code CURRENT_DATE}, a
 * column's {@code DEFAULT CURRENT_DATE} and a {@code timestamptz::date} cast
 * give the same answer the Java side does.
 *
 * <p>Nothing edits {@code school.timezone} after onboarding, so the memory is
 * never invalidated. A path that changes it must forget the school here too.
 */
public final class SchoolZones {

    /** {@code school.timezone}'s own column default, and the zone of a caller with no school. */
    public static final ZoneId DEFAULT = ZoneId.of("Asia/Kolkata");

    private static final ConcurrentMap<UUID, ZoneId> KNOWN = new ConcurrentHashMap<>();

    private SchoolZones() {}

    /**
     * The school's zone, looked up on {@code connection} — which must already
     * have the school's chain on its search_path — when it is not yet known.
     * A school that is not there (yet) answers the default without being
     * remembered, so the onboarding transaction that creates it does not pin a
     * guess.
     */
    public static ZoneId of(Connection connection, UUID schoolId) throws SQLException {
        if (schoolId == null) return DEFAULT;
        ZoneId known = KNOWN.get(schoolId);
        if (known != null) return known;
        try (PreparedStatement ps = connection.prepareStatement("SELECT timezone FROM school WHERE id = ?")) {
            ps.setObject(1, schoolId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return DEFAULT;
                ZoneId zone = parse(rs.getString(1));
                KNOWN.put(schoolId, zone);
                return zone;
            }
        }
    }

    /**
     * A region id, or the default. A bare offset is refused rather than passed
     * on: Postgres reads {@code '+05:30'} as POSIX, which is five and a half
     * hours <em>west</em> of Greenwich.
     */
    static ZoneId parse(String id) {
        if (id == null || id.isBlank()) return DEFAULT;
        try {
            ZoneId zone = ZoneId.of(id.trim());
            return zone instanceof ZoneOffset ? DEFAULT : zone;
        } catch (DateTimeException e) {
            return DEFAULT;
        }
    }
}
