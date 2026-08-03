package com.schoolsoft.featureflags.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.platform.tenancy.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class FeatureFlags {

    private record CacheEntry(boolean value, long expiresAtMs) {}
    private static final long TTL_MS = Duration.ofSeconds(60).toMillis();

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public FeatureFlags(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

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

    private static final RowMapper<FeatureFlagDto> FLAG_MAPPER = (rs, i) -> new FeatureFlagDto(
        rs.getString("code"),
        rs.getBoolean("enabled"),
        rs.getString("description"),
        readOverrides(rs.getString("school_overrides")),
        rs.getInt("rollout_pct")
    );

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> readOverrides(String json) {
        if (json == null) return Map.of();
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public List<FeatureFlagDto> list() {
        return jdbc.query(
            "SELECT code, enabled, description, school_overrides, rollout_pct FROM feature_flag ORDER BY code",
            FLAG_MAPPER
        );
    }

    /** Upserts a flag and invalidates the read-side cache so the new value is visible immediately. */
    public FeatureFlagDto upsert(String code, boolean enabled, String description, Map<String, Boolean> schoolOverrides, int rolloutPct) {
        try {
            PGobject overridesJson = new PGobject();
            overridesJson.setType("jsonb");
            overridesJson.setValue(json.writeValueAsString(schoolOverrides == null ? Map.of() : schoolOverrides));
            jdbc.update(
                "INSERT INTO feature_flag (code, enabled, description, school_overrides, rollout_pct) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (code) DO UPDATE SET enabled = EXCLUDED.enabled, description = EXCLUDED.description, " +
                "  school_overrides = EXCLUDED.school_overrides, rollout_pct = EXCLUDED.rollout_pct, updated_at = now()",
                code, enabled, description, overridesJson, rolloutPct
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        cache.keySet().removeIf(k -> k.startsWith(code + "::"));
        return jdbc.queryForObject(
            "SELECT code, enabled, description, school_overrides, rollout_pct FROM feature_flag WHERE code = ?",
            FLAG_MAPPER, code
        );
    }
}
