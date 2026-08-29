package com.schoolsoft.assessment.internal;

import com.schoolsoft.iam.api.Authz;
import com.schoolsoft.platform.web.ForbiddenException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Who may reopen something that was sealed.
 *
 * <p>Reopening is the high-risk direction in both the assessment and the
 * report-card lifecycles: marks a family has already been shown become
 * editable again. The rule is the same act in both places, so it is stated
 * once here rather than twice inside two repositories.</p>
 *
 * <p>Extracted from {@code AssessmentRepository}, where it was a role check in
 * the middle of a status-transition query. A refusal is an authorization
 * decision and belongs in something named for it.</p>
 */
@Service
public class AssessmentAuthorizer {

    /** Roles allowed to unseal. Mirrors {@code MarkService.EXAM_AUTHORITY_ROLES}. */
    private static final List<String> UNLOCK_ROLES = MarkService.EXAM_AUTHORITY_ROLES;

    private final Authz authz;

    public AssessmentAuthorizer(Authz authz) { this.authz = authz; }

    /**
     * @param what the thing being reopened, for the message the caller reads
     *             ("a locked assessment", "a published report card")
     */
    public void requireMayReopen(String what) {
        if (authz.rolesOfCurrentUser().stream().noneMatch(UNLOCK_ROLES::contains)) {
            throw new ForbiddenException(
                "Your role cannot reopen " + what + " (needs one of " + UNLOCK_ROLES + ")");
        }
    }
}
