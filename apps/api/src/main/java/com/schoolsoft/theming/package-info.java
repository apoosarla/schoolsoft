/**
 * Theming — per-school white-label config (logo, colours, app name, custom
 * domain, email-from). The web apps fetch this at boot keyed by host header
 * (matching {@code school_theme.custom_domain}) or by authenticated school id.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Theming")
package com.schoolsoft.theming;
