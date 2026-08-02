package com.schoolsoft.theming.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/theming")
public class ThemeController {

    private final JdbcTemplate jdbc;
    public ThemeController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
}
