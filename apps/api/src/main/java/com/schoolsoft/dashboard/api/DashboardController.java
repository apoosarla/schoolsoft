package com.schoolsoft.dashboard.api;

import com.schoolsoft.dashboard.internal.DashboardRepository;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/dashboards")
public class DashboardController {

    private final DashboardRepository repo;
    public DashboardController(DashboardRepository repo) { this.repo = repo; }

    @GetMapping("/schools/{schoolId}/overview")
    public SchoolOverviewDto overview(@PathVariable UUID schoolId) {
        return repo.overview(schoolId);
    }
}
