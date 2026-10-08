package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import com.schoolsoft.certification.support.CertificationFixture.SchoolSeed;
import com.schoolsoft.iam.internal.OtpStore;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/** CERT-STF — staff operations. */
class StaffCertTest extends AbstractCertificationTest {

    @Autowired private OtpStore otps;

    /** Adds a member of staff through the office's own endpoint and answers their staff id. */
    private UUID hire(SchoolSeed school, String firstName, String tag, String... roleCodes) {
        var created = post("/v1/people/staff", body(
            "schoolId", school.id(), "firstName", firstName, "lastName", "Cert",
            "email", tag + "@stf.cert.test", "roleCodes", List.of(roleCodes)), principalToken(school));
        assertThat(created.getStatusCode()).as("hiring %s", firstName).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }

    private UUID accountOf(UUID staffId) {
        return queryOne("SELECT id FROM user_account WHERE subject_type = 'staff' AND subject_id = ?",
            UUID.class, staffId);
    }

    private static String tag(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test @Tag("P1")
    void cert_STF_01_teacherWithoutATeachingRoleCannotBeAssignedToASection() {
        var school = cbse();
        String head = principalToken(school);
        UUID section = currentFocusSection(school);
        UUID subject = subjectOf(school, school.subjectCodes().get(0));

        // One act puts somebody on the books: a record, a number from the
        // school's series, a way to sign in, and the role they were hired into.
        String teacherTag = tag("stf01t");
        var created = post("/v1/people/staff", body(
            "schoolId", school.id(), "firstName", "Tara", "lastName", "Cert",
            "email", teacherTag + "@stf.cert.test", "employmentType", "contract",
            "roleCodes", List.of("subject_teacher")), head);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID teacher = UUID.fromString(created.getBody().get("id").asText());
        assertThat(created.getBody().get("employeeNo").asText()).startsWith("EMP");
        assertThat(created.getBody().get("campusId").asText()).isEqualTo(school.mainCampusId().toString());
        assertThat(queryList("SELECT role_code FROM staff_role WHERE staff_id = ? AND revoked_at IS NULL",
            String.class, teacher)).containsExactly("subject_teacher");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'staff.created' AND target_id = ?", teacher))
            .isEqualTo(1);

        // And they can sign in with the address they were hired under.
        String email = teacherTag + "@stf.cert.test";
        assertThat(post("/v1/auth/otp/verify", Map.of("identifier", email, "chainSlug", seed.chainSlug(),
            "code", otps.issue(email, seed.chainSlug())), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The same address cannot be given to a second person, and nobody is
        // hired with no way in at all.
        assertThat(post("/v1/people/staff", body("schoolId", school.id(), "firstName", "Twin",
            "email", email), head).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("/v1/people/staff", body("schoolId", school.id(), "firstName", "Nobody"), head)
            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Hiring is the heads'. A teacher cannot do it, and a registrar — who
        // may not manage roles either — cannot.
        assertThat(post("/v1/people/staff", body("schoolId", school.id(), "firstName", "Sneak",
            "email", tag("x") + "@stf.cert.test"), teacherToken(school, 0)).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/people/staff", body("schoolId", school.id(), "firstName", "Sneak",
            "email", tag("x") + "@stf.cert.test"), registrarToken(school)).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        // Somebody on the books with no teaching role is not a teacher, and a
        // section cannot be given to them.
        UUID clerk = hire(school, "Clerk", tag("stf01c"));
        var refused = post("/v1/tenancy/sections/" + section + "/teachers",
            body("subjectId", subject, "teacherStaffId", clerk, "isPrimary", false), head);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("message").asText()).contains("teaching role");
        assertThat(count("SELECT count(*) FROM section_subject_teacher WHERE teacher_staff_id = ?", clerk)).isZero();

        // A non-teaching role does not change that; a teaching one does.
        assertThat(post("/v1/iam/staff-roles/assign", body("staffId", clerk, "schoolId", school.id(),
            "roleCode", "librarian", "scopeType", "school", "scopeId", school.id(),
            "reason", "Covers the library desk"), head).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post("/v1/tenancy/sections/" + section + "/teachers",
            body("subjectId", subject, "teacherStaffId", clerk, "isPrimary", false), head).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);

        try {
            assertThat(post("/v1/tenancy/sections/" + section + "/teachers",
                body("subjectId", subject, "teacherStaffId", teacher, "isPrimary", false), head).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        } finally {
            inChainDo(jdbc -> jdbc.update("DELETE FROM section_subject_teacher WHERE teacher_staff_id = ?", teacher));
        }

        // The record is edited as a form: a stale copy is refused rather than
        // quietly winning, and a corrected address moves the sign-in with it.
        int version = created.getBody().get("version").asInt();
        String corrected = tag("stf01n") + "@stf.cert.test";
        var edited = put("/v1/people/staff/" + teacher, body("firstName", "Tara", "lastName", "Renamed",
            "email", corrected, "employmentType", "permanent", "version", version), head);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().get("lastName").asText()).isEqualTo("Renamed");
        assertThat(queryOne("SELECT email FROM user_account WHERE id = ?", String.class, accountOf(teacher)))
            .isEqualTo(corrected);
        assertThat(put("/v1/people/staff/" + teacher, body("firstName", "Tara", "lastName", "Stale",
            "email", corrected, "version", version), head).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
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
    void cert_STF_04_staffExitRevokesAccessAndPreservesHistory() {
        var school = cbse();
        String head = principalToken(school);
        UUID section = currentFocusSection(school);
        UUID subject = subjectOf(school, school.subjectCodes().get(0));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate lastDay = today.plusDays(3);

        String leaverTag = tag("stf04l");
        UUID leaver = hire(school, "Leela", leaverTag, "class_teacher");
        UUID successor = hire(school, "Sunil", tag("stf04s"), "class_teacher");
        UUID clerk = hire(school, "Clerk", tag("stf04c"));
        String leaverToken = tokenFor(school, accountOf(leaver), "staff");
        String successorToken = tokenFor(school, accountOf(successor), "staff");
        String refresh = jwt.issueRefresh(accountOf(leaver), seed.chainId().toString(), seed.chainSchema());

        try {
            // What the leaver holds: a section as its class teacher, and a
            // period on the timetable.
            assertThat(post("/v1/tenancy/sections/" + section + "/teachers",
                body("subjectId", subject, "teacherStaffId", leaver, "isPrimary", true), head).getStatusCode())
                .isEqualTo(HttpStatus.OK);
            var slot = post("/v1/timetable/slots", body(
                "sectionId", section, "subjectId", subject, "teacherStaffId", leaver,
                "dayOfWeek", 2, "periodNo", 11, "startsAt", "17:00:00", "endsAt", "17:40:00",
                "room", "STF04-" + leaverTag, "effectiveFrom", today.minusDays(14).toString()), head);
            assertThat(slot.getStatusCode()).isEqualTo(HttpStatus.OK);
            UUID slotId = UUID.fromString(slot.getBody().get("id").asText());
            int version = queryOne("SELECT version FROM staff WHERE id = ?", Integer.class, leaver);

            var duties = get("/v1/people/staff/" + leaver + "/duties?lastWorkingDate=" + lastDay, head);
            assertThat(duties.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(duties.getBody()).hasSize(2);

            // An exit says why, and is refused while it would leave a section
            // and a period with nobody — or with somebody who does not teach.
            assertThat(post("/v1/people/staff/" + leaver + "/exit",
                body("lastWorkingDate", lastDay.toString(), "version", version), head).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
            var unowned = post("/v1/people/staff/" + leaver + "/exit",
                body("lastWorkingDate", lastDay.toString(), "reason", "Relocating", "version", version), head);
            assertThat(unowned.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(unowned.getBody().get("message").asText()).contains("Name who takes them over");
            assertThat(post("/v1/people/staff/" + leaver + "/exit", body("lastWorkingDate", lastDay.toString(),
                "reason", "Relocating", "successorStaffId", clerk, "version", version), head).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
            assertThat(post("/v1/people/staff/" + leaver + "/exit", body("lastWorkingDate", lastDay.toString(),
                "reason", "Relocating", "successorStaffId", successor, "version", version),
                teacherToken(school, 0)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(queryOne("SELECT left_on FROM staff WHERE id = ?", java.sql.Date.class, leaver)).isNull();

            var exited = post("/v1/people/staff/" + leaver + "/exit", body("lastWorkingDate", lastDay.toString(),
                "reason", "Relocating", "successorStaffId", successor, "version", version), head);
            assertThat(exited.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(exited.getBody().get("leftOn").asText()).isEqualTo(lastDay.toString());
            assertThat(exited.getBody().get("successorStaffId").asText()).isEqualTo(successor.toString());

            // Filing it again is a retry, not an error; filing a different day is a conflict.
            assertThat(post("/v1/people/staff/" + leaver + "/exit", body("lastWorkingDate", lastDay.toString(),
                "reason", "Relocating", "successorStaffId", successor, "version", version), head).getStatusCode())
                .isEqualTo(HttpStatus.OK);
            assertThat(post("/v1/people/staff/" + leaver + "/exit", body("lastWorkingDate",
                lastDay.plusDays(5).toString(), "reason", "Relocating", "version", version + 1), head)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            // The exit is on the record with its reason.
            assertThat(queryList("SELECT reason FROM audit_log WHERE action = 'staff.exit' AND target_id = ?",
                String.class, leaver)).isNotEmpty().allMatch("Relocating"::equals);

            // The handover is dated, not a rewrite. The leaver's rows end on
            // their last day and still carry their name; the successor's open
            // the morning after, class-teacher flag included.
            assertThat(queryOne("SELECT effective_to FROM section_subject_teacher WHERE teacher_staff_id = ? "
                + "AND section_id = ?", java.sql.Date.class, leaver, section).toLocalDate()).isEqualTo(lastDay);
            assertThat(queryOne("SELECT effective_from FROM section_subject_teacher WHERE teacher_staff_id = ? "
                + "AND section_id = ? AND is_primary", java.sql.Date.class, successor, section).toLocalDate())
                .isEqualTo(lastDay.plusDays(1));
            assertThat(queryOne("SELECT effective_to FROM timetable_slot WHERE id = ? AND teacher_staff_id = ?",
                java.sql.Date.class, slotId, leaver).toLocalDate()).isEqualTo(lastDay);
            assertThat(queryOne("SELECT effective_from FROM timetable_slot WHERE teacher_staff_id = ? "
                + "AND section_id = ? AND period_no = 11", java.sql.Date.class, successor, section).toLocalDate())
                .isEqualTo(lastDay.plusDays(1));

            // Until the last day nothing has changed for either of them: the
            // leaver still reads their section, the successor does not yet.
            assertThat(get("/v1/enrolment/sections/" + section, leaverToken).getStatusCode())
                .isEqualTo(HttpStatus.OK);
            assertThat(get("/v1/enrolment/sections/" + section, successorToken).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(post("/v1/auth/refresh", Map.of("refreshToken", refresh), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);

            // The morning after. No job has run and no row has been flipped.
            String laterRefresh = jwt.issueRefresh(accountOf(leaver), seed.chainId().toString(), seed.chainSchema());
            String email = leaverTag + "@stf.cert.test";
            String code = otps.issue(email, seed.chainSlug());
            clock.pin(lastDay.plusDays(1).atTime(9, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant());
            try {
                // A token still in the leaver's browser opens nothing, and
                // neither a refresh nor a fresh code gets them a new one.
                assertThat(get("/v1/enrolment/sections/" + section, leaverToken).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(get("/v1/people/staff?schoolId=" + school.id(), leaverToken).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(post("/v1/auth/refresh", Map.of("refreshToken", laterRefresh), null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(post("/v1/auth/otp/verify", Map.of("identifier", email, "chainSlug", seed.chainSlug(),
                    "code", code), null).getStatusCode().is4xxClientError()).isTrue();

                // The section is the successor's now, and the office's pickers
                // stop offering the person who left while the register of
                // everybody who ever worked here still has them.
                assertThat(get("/v1/enrolment/sections/" + section, successorToken).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
                assertThat(get("/v1/people/staff?schoolId=" + school.id() + "&current=true", head).getBody()
                    .findValuesAsText("id")).doesNotContain(leaver.toString()).contains(successor.toString());
                assertThat(get("/v1/people/staff?schoolId=" + school.id(), head).getBody()
                    .findValuesAsText("id")).contains(leaver.toString());
                assertThat(get("/v1/tenancy/sections/" + section + "/teachers", head).getBody()
                    .findValuesAsText("teacherStaffId")).doesNotContain(leaver.toString())
                    .contains(successor.toString());
            } finally {
                clock.release();
            }

            // What they held while they were here is still theirs on the record.
            assertThat(count("SELECT count(*) FROM staff_role WHERE staff_id = ? AND revoked_at IS NULL", leaver))
                .isEqualTo(1);
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM timetable_slot WHERE teacher_staff_id IN (?, ?)", leaver, successor);
                jdbc.update("DELETE FROM section_subject_teacher WHERE teacher_staff_id IN (?, ?)", leaver, successor);
            });
        }
    }

    @Test @Tag("P1")
    void cert_STF_05_teacherSeesOnlyTheirOwnSectionsAndMarks() {
        var school = cbse();
        UUID teacherStaffId = school.teacherStaffIds().get(0);
        String teacher = teacherToken(school, 0);
        String head = principalToken(school);

        UUID mine = queryOne(
            "SELECT sst.section_id FROM section_subject_teacher sst " +
            "JOIN section s ON s.id = sst.section_id " +
            "WHERE sst.teacher_staff_id = ? AND s.school_id = ? LIMIT 1",
            UUID.class, teacherStaffId, school.id());

        // A section this teacher neither holds a subject in nor is timetabled
        // for. The focus sections are out by construction — every teacher has a
        // slot in those — so this is one of the sections the round-robin
        // assignment left them out of.
        UUID theirs = queryOne(
            "SELECT s.id FROM section s WHERE s.school_id = ? " +
            "  AND s.id NOT IN (SELECT section_id FROM section_subject_teacher WHERE teacher_staff_id = ?) " +
            "  AND s.id NOT IN (SELECT section_id FROM timetable_slot WHERE teacher_staff_id = ?) " +
            "  AND EXISTS (SELECT 1 FROM enrolment e WHERE e.section_id = s.id AND e.status = 'active') LIMIT 1",
            UUID.class, school.id(), teacherStaffId, teacherStaffId);

        assertThat(mine).as("the fixture gives teacher 1 at least one section").isNotNull();
        assertThat(theirs).as("the fixture leaves teacher 1 out of at least one section").isNotNull();

        // Their own section reads exactly as before.
        assertThat(get("/v1/enrolment/sections/" + mine, teacher).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Another teacher's section is refused outright — a roster, the day's
        // register, and the section's assessments alike.
        assertThat(get("/v1/enrolment/sections/" + theirs, teacher).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/attendance?sectionId=" + theirs + "&onDate=2026-08-10", teacher).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/assessment?sectionId=" + theirs, teacher).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        // ...and so is the marks grid hanging off it. The scenario makes its
        // own assessment rather than borrowing a neighbour's.
        UUID assessmentId = UUID.fromString(post("/v1/assessment", body(
            "schoolId", school.id(), "sectionId", theirs, "subjectId", subjectOf(school, "MATH"),
            "termId", termOf(school, school.currentAy().code(), "T2"),
            "strategyCode", "CBSE-CCE-2024", "name", "STF-05 — another teacher's paper",
            "assessmentType", "UT", "maxMarks", 20.0, "weightPct", 10.0,
            "scheduledOn", "2026-09-21"), head).getBody().get("id").asText());
        UUID componentId = UUID.fromString(post("/v1/assessment/" + assessmentId + "/components",
            body("code", "THEORY", "name", "Theory paper", "maxMarks", 20.0, "weightPct", 100.0,
                "sortOrder", 1), head).getBody().get("id").asText());

        assertThat(get("/v1/assessment/" + assessmentId, teacher).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/assessment/" + assessmentId + "/components", teacher).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/v1/assessment/components/" + componentId + "/marks", teacher).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        // Writing to it is refused too. Mark entry used to check nothing at all.
        UUID theirStudentForMarks = firstStudentIn(theirs);
        assertThat(post("/v1/assessment/components/" + componentId + "/marks", body(
            "schoolId", school.id(), "studentId", theirStudentForMarks, "rawMarks", 15.0), teacher)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // In their own section, a subject somebody else teaches is read-only to
        // them: the English teacher does not correct the Maths marks.
        UUID notTheirSubject = queryOne(
            "SELECT sub.id FROM subject sub WHERE sub.school_id = ? " +
            "  AND sub.id NOT IN (SELECT subject_id FROM section_subject_teacher " +
            "                     WHERE teacher_staff_id = ? AND section_id = ?) " +
            "  AND sub.id NOT IN (SELECT subject_id FROM timetable_slot " +
            "                     WHERE teacher_staff_id = ? AND section_id = ?) LIMIT 1",
            UUID.class, school.id(), teacherStaffId, mine, teacherStaffId, mine);
        UUID otherSubjectPaper = UUID.fromString(post("/v1/assessment", body(
            "schoolId", school.id(), "sectionId", mine, "subjectId", notTheirSubject,
            "termId", termOf(school, school.currentAy().code(), "T2"),
            "strategyCode", "CBSE-CCE-2024", "name", "STF-05 — a colleague's subject",
            "assessmentType", "UT", "maxMarks", 20.0, "weightPct", 10.0,
            "scheduledOn", "2026-09-21"), head).getBody().get("id").asText());
        UUID otherSubjectComponent = UUID.fromString(post("/v1/assessment/" + otherSubjectPaper + "/components",
            body("code", "THEORY", "name", "Theory paper", "maxMarks", 20.0, "weightPct", 100.0,
                "sortOrder", 1), head).getBody().get("id").asText());
        assertThat(get("/v1/assessment/components/" + otherSubjectComponent + "/marks", teacher).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(post("/v1/assessment/components/" + otherSubjectComponent + "/marks", body(
            "schoolId", school.id(), "studentId", firstStudentIn(mine), "rawMarks", 15.0), teacher)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // A student in that section is not theirs to look through either.
        UUID theirStudent = firstStudentIn(theirs);
        assertThat(get("/v1/attendance/students/" + theirStudent + "?from=2026-08-01&to=2026-08-31", teacher)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The head of school stands above the teaching layer and is not confined.
        assertThat(get("/v1/enrolment/sections/" + theirs, head).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/assessment/components/" + componentId + "/marks", head).getStatusCode())
            .isEqualTo(HttpStatus.OK);

        // Plain student lookup is deliberately *not* narrowed: a staffroom
        // finds a guardian's number for a child from another class.
        assertThat(get("/v1/people/students?schoolId=" + school.id(), teacher).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/v1/people/students/" + theirStudent, teacher).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }

    @Test @Tag("P1")
    void cert_STF_06_headOfSchoolAggregatesAcrossTheirSchoolAndNothingBeyond() {
        String token = principalToken(cbse());
        var overview = get("/v1/dashboards/schools/" + cbse().id() + "/overview", token);
        assertThat(overview.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The oracle asks the question the dashboard asks: who is on the
        // register today. A child whose withdrawal is filed for a last working
        // day still to come is one of them, and counted as a status they are
        // not — which also makes the attendance percentage this number is the
        // denominator of read over 100%.
        long activeEnrolments = overview.getBody().get("activeEnrolments").asLong();
        assertThat(activeEnrolments)
            .isEqualTo(count(
                "SELECT count(*) FROM enrolment WHERE school_id = ? "
                + "AND starts_on <= current_date "
                + "AND COALESCE(ends_on, 'infinity'::date) >= current_date", cbse().id()));
        assertThat(activeEnrolments).isGreaterThan(
            count("SELECT count(*) FROM enrolment WHERE school_id = ? AND status = 'active'", cbse().id()));

        // The same call pointed at the sibling school returns that school's rows to nobody:
        // row-level security answers with zeros rather than another school's aggregate.
        var otherSchool = get("/v1/dashboards/schools/" + cie().id() + "/overview", token);
        assertThat(otherSchool.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherSchool.getBody().get("activeEnrolments").asLong()).isZero();
    }
}
