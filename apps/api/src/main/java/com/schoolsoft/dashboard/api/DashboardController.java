package com.schoolsoft.dashboard.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.schoolsoft.dashboard.internal.DashboardRepository;
import com.schoolsoft.iam.api.PermissionChecker;
import com.schoolsoft.platform.security.Perm;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/dashboards")
public class DashboardController {

    private final DashboardRepository repo;
    private final PermissionChecker perms;

    public DashboardController(DashboardRepository repo, PermissionChecker perms) {
        this.repo = repo;
        this.perms = perms;
    }

    @PreAuthorize("@perm.can('dashboard.view')")
    @GetMapping("/schools/{schoolId}/overview")
    public SchoolOverviewDto overview(@PathVariable UUID schoolId) {
        // A teacher holds dashboard.view for the register and comms cards; the
        // school's takings and admissions pipeline are the office's. Each
        // section follows the grant that opens its own module's reports, so a
        // custom role lands on the right side without a deploy.
        return repo.overview(schoolId,
            perms.holdsUnrestricted(Perm.FEE_REPORT_VIEW),
            perms.holdsUnrestricted(Perm.ADMISSION_VIEW));
    }
}
