/**
 * Platform module — cross-cutting infrastructure shared by all business modules.
 *
 * Marked as a shared module in the Modulith manifest so other modules may
 * depend on its public types without violating package-boundary checks.
 * Contains tenant resolution, datasource wiring, security filters, web error
 * handling, and the chain-aware migration runner.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Platform",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.schoolsoft.platform;
