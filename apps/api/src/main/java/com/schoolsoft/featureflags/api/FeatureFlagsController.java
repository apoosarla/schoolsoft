package com.schoolsoft.featureflags.api;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/feature-flags")
public class FeatureFlagsController {

    private final FeatureFlags flags;
    public FeatureFlagsController(FeatureFlags flags) { this.flags = flags; }

    @GetMapping
    public List<FeatureFlagDto> list() {
        return flags.list();
    }

    public record UpsertRequest(
        @NotBlank String code, boolean enabled, String description, Map<String, Boolean> schoolOverrides, int rolloutPct
    ) {}

    @PutMapping
    public FeatureFlagDto upsert(@RequestBody UpsertRequest req) {
        return flags.upsert(req.code(), req.enabled(), req.description(), req.schoolOverrides(), req.rolloutPct());
    }

    @GetMapping("/{code}/enabled")
    public Map<String, Boolean> isEnabled(@PathVariable String code) {
        return Map.of("enabled", flags.isEnabled(code));
    }
}
