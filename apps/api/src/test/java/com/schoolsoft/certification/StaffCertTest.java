package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.UUID;
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
    void cert_STF_02_staffLeaveIsApprovedByTheRightApproverAndReflectedInAttendance() {
        UUID staffId = cbse().teacherStaffIds().get(6);
        String applicant = teacherToken(cbse(), 6);
        String principal = principalToken(cbse());

        var applied = post("/v1/attendance/leave", body(
            "schoolId", cbse().id(), "subjectType", "staff", "subjectId", staffId,
            "fromDate", "2026-08-06", "toDate", "2026-08-07", "reason", "Family function"), applicant);
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID leaveId = UUID.fromString(applied.getBody().get("id").asText());

        // Nobody approves their own leave, and a subject teacher does not
        // approve a colleague's either.
        assertThat(post("/v1/attendance/leave/" + leaveId + "/decide",
            body("status", "approved", "approverStaffId", staffId), applicant).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/attendance/leave/" + leaveId + "/decide",
            body("status", "approved", "approverStaffId", cbse().teacherStaffIds().get(1)),
            teacherToken(cbse(), 1)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Nor does the approver on record get to be somebody other than the decider.
        assertThat(post("/v1/attendance/leave/" + leaveId + "/decide",
            body("status", "approved", "approverStaffId", cbse().teacherStaffIds().get(1)), principal)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count("SELECT count(*) FROM staff_attendance WHERE staff_id = ? "
            + "AND on_date BETWEEN '2026-08-06' AND '2026-08-07'", staffId)).isZero();

        var approved = post("/v1/attendance/leave/" + leaveId + "/decide",
            body("status", "approved", "approverStaffId", cbse().principalStaffId()), principal);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().get("approverStaffId").asText())
            .isEqualTo(cbse().principalStaffId().toString());

        assertThat(queryList("SELECT status FROM staff_attendance WHERE staff_id = ? "
            + "AND on_date BETWEEN '2026-08-06' AND '2026-08-07' ORDER BY on_date", String.class, staffId))
            .containsExactly("leave", "leave");

        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM staff_attendance WHERE leave_application_id = ?", leaveId);
            jdbc.update("DELETE FROM timetable_cover WHERE leave_application_id = ?", leaveId);
            jdbc.update("DELETE FROM leave_application WHERE id = ?", leaveId);
        });
    }

    @Test @Tag("P1")
    void cert_STF_03_approvedTeacherLeaveSurfacesPeriodsForSubstitution() {
        String principal = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());
        String onDate = "2026-08-03";                       // a Monday

        // The teacher timetabled for this section's first period that day.
        UUID absentStaffId = queryOne(
            "SELECT teacher_staff_id FROM timetable_slot WHERE section_id = ? AND day_of_week = 1 "
            + "AND period_no = 1", UUID.class, sectionId);

        var applied = post("/v1/attendance/leave", body(
            "schoolId", cbse().id(), "subjectType", "staff", "subjectId", absentStaffId,
            "fromDate", onDate, "toDate", onDate, "reason", "Jury duty"), principal);
        UUID leaveId = UUID.fromString(applied.getBody().get("id").asText());

        // Until it is approved, this teacher's periods are nobody else's problem.
        assertThat(needsFor(onDate, absentStaffId, principal)).isEmpty();

        assertThat(post("/v1/attendance/leave/" + leaveId + "/decide",
            body("status", "approved", "approverStaffId", cbse().principalStaffId()), principal)
            .getStatusCode()).isEqualTo(HttpStatus.OK);

        var theirPeriods = needsFor(onDate, absentStaffId, principal);
        assertThat(theirPeriods).isNotEmpty();
        assertThat(theirPeriods).anyMatch(need -> need.get("sectionId").asText().equals(sectionId.toString()));
        theirPeriods.forEach(need -> {
            assertThat(need.get("leaveApplicationId").asText()).isEqualTo(leaveId.toString());
            assertThat(need.has("cover")).as("nobody has been asked yet").isFalse();
            // Whoever is offered is free in that period and not away themselves.
            assertThat(need.get("candidates")).isNotEmpty();
            need.get("candidates").forEach(candidate ->
                assertThat(candidate.get("staffId").asText()).isNotEqualTo(absentStaffId.toString()));
        });

        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM staff_attendance WHERE leave_application_id = ?", leaveId);
            jdbc.update("DELETE FROM leave_application WHERE id = ?", leaveId);
        });
    }

    /** The day's cover needs raised by one teacher's absence. */
    private java.util.List<com.fasterxml.jackson.databind.JsonNode> needsFor(
        String onDate, UUID absentStaffId, String token
    ) {
        var needs = get("/v1/timetable/cover/needs?schoolId=" + cbse().id() + "&date=" + onDate, token);
        assertThat(needs.getStatusCode()).isEqualTo(HttpStatus.OK);
        var theirs = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        needs.getBody().forEach(need -> {
            if (need.get("absentStaffId").asText().equals(absentStaffId.toString())) theirs.add(need);
        });
        return theirs;
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
