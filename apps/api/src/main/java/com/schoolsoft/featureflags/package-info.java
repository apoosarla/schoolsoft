/**
 * Feature flags — per-tenant boolean toggles with school overrides and a
 * percentage rollout knob. Phase 2 will replace this with a managed flag
 * service (Flagsmith / Unleash) once we need targeted experiments.
 */
@org.springframework.modulith.ApplicationModule(displayName = "FeatureFlags")
package com.schoolsoft.featureflags;
