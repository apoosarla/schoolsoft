package com.schoolsoft.featureflags.api;

import com.schoolsoft.platform.tenancy.TenantContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FeatureFlags {

    private record CacheEntry(boolean value, long expiresAtMs) {}
    private static final long TTL_MS = Duration.ofSeconds(60).toMillis();

    private final JdbcTemplate jdbc;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public FeatureFlags(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean isEnabled(String code) {
        var snap = TenantContext.get();
        UUID schoolId = snap == null ? null : snap.schoolId();
        String key = code + "::" + (schoolId == null ? "_" : schoolId);
        CacheEntry hit = cache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAtMs() > now) return hit.value();

        boolean v = jdbc.query(
            "SELECT enabled, school_overrides FROM feature_flag WHERE code = ?",
            rs -> {
                if (!rs.next()) return false;
                boolean base = rs.getBoolean("enabled");
                String overrides = rs.getString("school_overrides");
                if (schoolId != null && overrides != null && overrides.contains(schoolId.toString())) {
                    return overrides.contains("\"" + schoolId + "\":true");
                }
                return base;
            },
            code
        );
        cache.put(key, new CacheEntry(v, now + TTL_MS));
        return v;
    }
}
