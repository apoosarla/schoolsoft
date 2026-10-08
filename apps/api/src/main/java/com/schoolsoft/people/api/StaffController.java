package com.schoolsoft.people.api;

import com.schoolsoft.audit.api.Audited;
import com.schoolsoft.people.internal.StaffService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The office's own hiring and leaving — the writes {@link PeopleController}'s
 * staff list never had. See {@code StaffService} for what each one does and
 * deliberately does not do.
 */
@RestController
@RequestMapping("/v1/people/staff")
public class StaffController {

    private final StaffService service;

    public StaffController(StaffService service) {
        this.service = service;
    }

    public record CreateStaffRequest(
        @NotNull UUID schoolId,
        /** Optional — the school's primary campus when omitted. */
        UUID campusId,
        /** Optional — the school's employee-number series issues one when omitted. */
        String employeeNo,
        @NotBlank String firstName,
        String lastName,
        String email,
        String phone,
        String employmentType,
        LocalDate joinedOn,
        /** Granting any needs {@code role.manage} on top of {@code staff.manage}. */
        List<String> roleCodes
    ) {}

    @PreAuthorize("@perm.can('staff.manage')")
    @PostMapping
    public StaffDto create(@Valid @RequestBody CreateStaffRequest req) {
        return service.create(new StaffService.NewStaff(
            req.schoolId(), req.campusId(), req.employeeNo(), req.firstName(), req.lastName(),
            req.email(), req.phone(), req.employmentType(), req.joinedOn(), req.roleCodes()));
    }

    public record UpdateStaffRequest(
        @NotBlank String firstName,
        String lastName,
        String email,
        String phone,
        String employmentType,
        LocalDate joinedOn,
        UUID campusId,
        @NotNull Integer version
    ) {}

    @PreAuthorize("@perm.can('staff.manage')")
    @PutMapping("/{id}")
    @Audited(action = "staff.updated", targetType = "staff", requireReason = false)
    public StaffDto update(@PathVariable UUID id, @Valid @RequestBody UpdateStaffRequest req) {
        return service.update(id, new StaffService.StaffEdit(
            req.firstName(), req.lastName(), req.email(), req.phone(), req.employmentType(),
            req.joinedOn(), req.campusId(), req.version()));
    }

    /**
     * What the person would leave without an owner if they went on
     * {@code lastWorkingDate} — asked before the exit is filed, so the office
     * sees what it is about to be asked to hand over.
     */
    @PreAuthorize("@perm.can('staff.manage')")
    @GetMapping("/{id}/duties")
    public List<StaffDutyHandover.Duty> duties(
        @PathVariable UUID id,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastWorkingDate
    ) {
        return service.duties(id, lastWorkingDate);
    }

    public record ExitRequest(
        @NotNull LocalDate lastWorkingDate,
        @NotBlank String reason,
        /** Required when the person still holds sections or periods after their last day. */
        UUID successorStaffId,
        @NotNull Integer version
    ) {}

    @PreAuthorize("@perm.can('staff.manage')")
    @PostMapping("/{id}/exit")
    @Audited(action = "staff.exit", targetType = "staff")
    public StaffDto exit(@PathVariable UUID id, @Valid @RequestBody ExitRequest req) {
        return service.exit(id, req.lastWorkingDate(), req.reason(), req.successorStaffId(), req.version());
    }
}
