package com.schoolsoft.theming.api;

import org.springframework.security.access.prepost.PreAuthorize;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/theming")
public class ThemeController {

    private final JdbcTemplate jdbc;
    public ThemeController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PreAuthorize("@perm.can('theme.view')")
    @GetMapping("/schools/{id}")
    public Map<String, Object> theme(@PathVariable UUID id) {
        var rows = jdbc.queryForList(
            "SELECT primary_color, accent_color, parent_app_name, custom_domain, email_from " +
            "FROM school_theme WHERE school_id = ?", id);
        if (rows.isEmpty()) {
            return Map.of("primaryColor", "#1f3a8a", "accentColor", "#f59e0b");
        }
        return rows.get(0);
    }

    public record UpdateThemeRequest(
        String primaryColor, String accentColor, String parentAppName, String customDomain, String emailFrom
    ) {}

    /**
     * Partial update — any field left null in the request keeps its current
     * value (or the schema default, for a school with no theme row yet).
     * The row-ensure and the COALESCE-update are two statements because a
     * single INSERT..ON CONFLICT can't distinguish "field omitted, keep
     * existing" from "field omitted, use default" once EXCLUDED has already
     * applied the VALUES-side default.
     */
    @PreAuthorize("@perm.can('theme.manage')")
    @PutMapping("/schools/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody UpdateThemeRequest req) {
        jdbc.update("INSERT INTO school_theme (school_id) VALUES (?) ON CONFLICT (school_id) DO NOTHING", id);
        jdbc.update(
            "UPDATE school_theme SET " +
            "  primary_color = COALESCE(?, primary_color), accent_color = COALESCE(?, accent_color), " +
            "  parent_app_name = COALESCE(?, parent_app_name), custom_domain = COALESCE(?, custom_domain), " +
            "  email_from = COALESCE(?, email_from), updated_at = now() " +
            "WHERE school_id = ?",
            req.primaryColor(), req.accentColor(), req.parentAppName(), req.customDomain(), req.emailFrom(), id
        );
        return theme(id);
    }
}
