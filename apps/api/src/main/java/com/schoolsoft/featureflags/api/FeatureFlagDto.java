package com.schoolsoft.featureflags.api;

import java.util.Map;

public record FeatureFlagDto(String code, boolean enabled, String description, Map<String, Boolean> schoolOverrides, int rolloutPct) {}
