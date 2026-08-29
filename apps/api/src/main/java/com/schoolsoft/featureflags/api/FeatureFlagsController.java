package com.schoolsoft.featureflags.api;

import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/feature-flags")
public class FeatureFlagsController {

    private final FeatureFlags flags;
    public FeatureFlagsController(FeatureFlags flags) { this.flags = flags; }

    @PreAuthorize("@perm.can('feature_flag.view')")
    @GetMapping
    public List<FeatureFlagDto> list() {
        return flags.list();
    }

    public record UpsertRequest(
        @NotBlank String code, boolean enabled, String description, Map<String, Boolean> schoolOverrides, int rolloutPct
    ) {}

    @PreAuthorize("@perm.can('feature_flag.manage')")
    @PutMapping
    public FeatureFlagDto upsert(@RequestBody UpsertRequest req) {
        return flags.upsert(req.code(), req.enabled(), req.description(), req.schoolOverrides(), req.rolloutPct());
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{code}/enabled")
    public Map<String, Boolean> isEnabled(@PathVariable String code) {
        return Map.of("enabled", flags.isEnabled(code));
    }
}
