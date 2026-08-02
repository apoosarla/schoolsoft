package com.schoolsoft.platform.web;

import java.time.Instant;
import java.util.Map;

public record ApiError(
    String code,
    String message,
    Instant at,
    Map<String, Object> details
) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now(), Map.of());
    }
}
