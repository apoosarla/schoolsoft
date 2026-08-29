package com.schoolsoft.attendance.internal;

import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.platform.tenancy.TenantContext;
import com.schoolsoft.platform.web.ForbiddenException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Who may decide a leave application.
 *
 * <p>Sits alongside {@link AttendanceAuthorizer} rather than inside
 * {@code AttendanceRepository}, where it used to live. A refusal is an
 * authorization decision, and a decision buried in a SQL helper is one nobody
 * reviewing "who may do this" will find — which is the whole reason the API
 * shipped with none at all.</p>
 *
 * <p>The {@code leave.decide} permission gets a caller as far as the endpoint.
 * This is the part a permission cannot express: it depends on <em>whose</em>
 * leave, and on the applicant not being the approver.</p>
 */
@Service
public class LeaveAuthorizer {

    /** Student leave is a class teacher's call; staff leave is not. */
    private static final List<String> STAFF_LEAVE_APPROVERS =
        List.of("principal", "vice_principal", "it_admin");
    private static final List<String> STUDENT_LEAVE_APPROVERS =
        List.of("principal", "vice_principal", "it_admin", "academic_coordinator", "class_teacher");

    private final Authz authz;

    public LeaveAuthorizer(Authz authz) { this.authz = authz; }

    /**
     * Asserts the caller may decide this application, and returns the staff id
     * the decision is recorded against — their own, always. A caller may not
     * put somebody else's name on a decision they made.
     *
     * @param claimedApproverStaffId what the request said, checked rather than trusted
     */
    public UUID requireApprover(String subjectType, UUID subjectId, UUID claimedApproverStaffId) {
        var snap = TenantContext.get();
        if (snap != null && (snap.trusted() || "platform_admin".equals(snap.subjectType()))) {
            return claimedApproverStaffId;
        }
        if (snap == null || !"staff".equals(snap.subjectType())) {
            throw new ForbiddenException("Only staff may decide a leave application");
        }

        UUID callerStaffId = authz.currentStaffId();
        if (callerStaffId == null) throw new ForbiddenException("No staff record behind this login");
        if ("staff".equals(subjectType) && callerStaffId.equals(subjectId)) {
            throw new ForbiddenException("A staff member cannot approve their own leave");
        }
        if (claimedApproverStaffId != null && !claimedApproverStaffId.equals(callerStaffId)) {
            throw new ForbiddenException(
                "The approver on record is whoever decides it; approverStaffId must be your own staff id");
        }

        var allowed = "staff".equals(subjectType) ? STAFF_LEAVE_APPROVERS : STUDENT_LEAVE_APPROVERS;
        if (authz.rolesOfCurrentUser().stream().noneMatch(allowed::contains)) {
            throw new ForbiddenException(
                "Your role cannot decide " + subjectType + " leave (needs one of " + allowed + ")");
        }
        return callerStaffId;
    }
}
