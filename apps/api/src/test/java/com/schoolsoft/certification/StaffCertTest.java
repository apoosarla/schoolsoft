package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-STF — staff operations. */
class StaffCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    @Disabled("There is no staff-creation endpoint (people module exposes reads only), and "
        + "assignSectionSubjectTeacher accepts any staff id without checking for a teaching role. New gap "
        + "found in Phase 0.")
    void cert_STF_01_teacherWithoutATeachingRoleCannotBeAssignedToASection() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-08 — leave is applied and decided, but approval writes nothing into staff_attendance "
        + "(Phase 3). The approver is also unchecked: any staff id may be passed as approverStaffId.")
    void cert_STF_02_staffLeaveIsApprovedByTheRightApproverAndReflectedInAttendance() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-07 — approved teacher leave does not surface the affected periods for substitution "
        + "(Phase 3).")
    void cert_STF_03_approvedTeacherLeaveSurfacesPeriodsForSubstitution() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-27 — no staff-exit path: nothing revokes access on the last working day or reassigns "
        + "section duties, and role revocation is not audited (Phase 3).")
    void cert_STF_04_staffExitRevokesAccessAndPreservesHistory() {
    }

    @Test @Tag("P1")
    @Disabled("Row-level security is school-scoped only: a teacher's token can read any section, student "
        + "or mark in their school, including another teacher's. Teacher-scope enforcement does not exist. "
        + "New gap found in Phase 0 — security-relevant.")
    void cert_STF_05_teacherSeesOnlyTheirOwnSectionsAndMarks() {
    }

    @Test @Tag("P1")
    void cert_STF_06_headOfSchoolAggregatesAcrossTheirSchoolAndNothingBeyond() {
        String token = principalToken(cbse());
        var overview = get("/v1/dashboards/schools/" + cbse().id() + "/overview", token);
        assertThat(overview.getStatusCode()).isEqualTo(HttpStatus.OK);

        long activeEnrolments = overview.getBody().get("activeEnrolments").asLong();
        assertThat(activeEnrolments)
            .isEqualTo(count("SELECT count(*) FROM enrolment WHERE school_id = ? AND status = 'active'", cbse().id()));

        // The same call pointed at the sibling school returns that school's rows to nobody:
        // row-level security answers with zeros rather than another school's aggregate.
        var otherSchool = get("/v1/dashboards/schools/" + cie().id() + "/overview", token);
        assertThat(otherSchool.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherSchool.getBody().get("activeEnrolments").asLong()).isZero();
    }
}
