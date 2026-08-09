package com.schoolsoft.iam.api;

import com.schoolsoft.iam.internal.RoleRepository;
import com.schoolsoft.platform.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Role catalog (seeded personas + custom roles) and staff-role assignment.
 * Screen-level access control for admin-web: each role names the set of
 * frontend routes ("screen keys") it unlocks; a staff member's effective
 * access is the union of screen_keys across every role granted to them via
 * {@code staff_role} — see {@link Authz#rolesOfCurrentUser()}.
 */
@RestController
@RequestMapping("/v1/iam")
public class RoleController {

    private final RoleRepository repo;
    private final Authz authz;

    public RoleController(RoleRepository repo, Authz authz) {
        this.repo = repo;
        this.authz = authz;
    }

    @GetMapping("/roles")
    public List<RoleDto> roles() {
        return repo.listRoles();
    }

    public record CreateRoleRequest(
        @NotBlank String code, @NotBlank String name, String description, @NotNull List<String> screenKeys
    ) {}

    @PostMapping("/roles")
    public RoleDto createRole(@RequestBody CreateRoleRequest req) {
        return repo.createRole(req.code(), req.name(), req.description(), req.screenKeys());
    }

    public record UpdateRoleRequest(@NotBlank String name, String description, @NotNull List<String> screenKeys) {}

    @PutMapping("/roles/{id}")
    public RoleDto updateRole(@PathVariable UUID id, @RequestBody UpdateRoleRequest req) {
        return repo.updateRole(id, req.name(), req.description(), req.screenKeys());
    }

    @DeleteMapping("/roles/{id}")
    public ResponseEntity<Void> deleteRole(@PathVariable UUID id) {
        repo.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/staff-roles")
    public List<StaffWithRolesDto> staffRoles(@RequestParam UUID schoolId) {
        return repo.listStaffWithRoles(schoolId);
    }

    public record AssignRoleRequest(@NotNull UUID staffId, @NotNull UUID schoolId, @NotBlank String roleCode) {}

    @PostMapping("/staff-roles/assign")
    public ResponseEntity<Void> assign(@RequestBody AssignRoleRequest req) {
        repo.assignRole(req.staffId(), req.schoolId(), req.roleCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/staff-roles/unassign")
    public ResponseEntity<Void> unassign(@RequestBody AssignRoleRequest req) {
        repo.unassignRole(req.staffId(), req.schoolId(), req.roleCode());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/screens")
    public Map<String, Object> myScreens() {
        List<String> roleCodes = authz.rolesOfCurrentUser();
        return Map.of("roleCodes", roleCodes, "screenKeys", repo.screensForRoleCodes(roleCodes));
    }

    /**
     * Resolves the caller's underlying person record. The JWT only carries
     * user_account.id — apps that need the staff/guardian/student row itself
     * (e.g. teacher-app looking up "my timetable" by staff id) call this once
     * after login rather than threading a lookup through every endpoint.
     */
    @GetMapping("/me")
    public Map<String, Object> me() {
        var snap = TenantContext.require();
        var subjectId = repo.subjectIdForUserAccount(snap.userAccountId());
        return Map.of(
            "userAccountId", snap.userAccountId(),
            "subjectType", snap.subjectType(),
            "subjectId", subjectId.map(Object::toString).orElse(""),
            "schoolId", snap.schoolId() == null ? "" : snap.schoolId().toString()
        );
    }
}
