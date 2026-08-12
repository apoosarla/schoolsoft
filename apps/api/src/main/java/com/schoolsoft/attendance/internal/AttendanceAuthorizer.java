package com.schoolsoft.attendance.internal;

import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ForbiddenException;
import com.schoolsoft.timetable.api.TeachingDuties;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Who may mark a register (TT-08).
 *
 * The rule is the school's own: the office marks anything, the class teacher
 * marks their homeroom, a subject teacher marks the period they teach — and a
 * substitute marks the period they have been handed, which is the whole reason
 * cover has to authorise as well as inform.
 *
 * Note what this is not: it is not teacher scoping. A {@code class_teacher}
 * grant here is school-wide because that is how {@code staff_role} records it
 * today; narrowing a teacher to their own sections is STF-05's open gap, and
 * doing half of it here would only hide it.
 */
@Service
public class AttendanceAuthorizer {

    /** Roles whose job is the whole school's register, not one section's. */
    private static final Set<String> OFFICE_ROLES =
        Set.of("principal", "vice_principal", "it_admin", "academic_coordinator", "class_teacher");

    private final Authz authz;
    private final TeachingDuties duties;

    public AttendanceAuthorizer(Authz authz, TeachingDuties duties) {
        this.authz = authz;
        this.duties = duties;
    }

    public void requireMayMark(UUID sectionId, LocalDate onDate, Integer periodNo) {
        var snap = TenantContext.get();
        if (snap == null) throw new ForbiddenException("No authenticated caller");
        if (snap.trusted() || "platform_admin".equals(snap.subjectType())) return;
        if (!"staff".equals(snap.subjectType())) {
            throw new ForbiddenException("Only staff may mark attendance");
        }

        List<String> roles = authz.rolesOfCurrentUser();
        if (roles.stream().anyMatch(OFFICE_ROLES::contains)) return;

        UUID staffId = authz.currentStaffId();
        if (staffId == null) throw new ForbiddenException("No staff record behind this login");
        if (duties.isPrimaryTeacherOf(staffId, sectionId)) return;
        if (duties.teachesOn(staffId, sectionId, onDate, periodNo)) return;
        if (duties.isCovering(staffId, sectionId, onDate, periodNo)) return;

        throw new ForbiddenException(
            periodNo == null
                ? "You do not teach this section on " + onDate + " and hold no cover for it"
                : "You are not timetabled for period " + periodNo + " on " + onDate
                  + " and hold no cover for it");
    }
}
