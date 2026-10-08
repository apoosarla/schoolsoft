package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-ATT — daily attendance. */
class AttendanceCertTest extends AbstractCertificationTest {

    private static final String MARK_DATE = "2026-08-03";      // a Monday inside the current AY

    @Test @Tag("P1")
    void cert_ATT_01_dayLevelMarkingIsIdempotentOnResubmission() {
        String token = teacherToken(cbse(), 0);
        UUID sectionId = currentFocusSection(cbse());
        List<UUID> students = studentsIn(sectionId);

        var first = post("/v1/attendance/mark/bulk", body(
            "schoolId", cbse().id(), "sectionId", sectionId, "onDate", MARK_DATE,
            "markedByStaffId", cbse().teacherStaffIds().get(0),
            "entries", students.stream().map(s -> Map.of("studentId", s, "status", "present")).toList()), token);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Re-submitting the same day corrects rather than duplicates.
        // The body still names somebody else as the marker; the server ignores
        // it and records the caller.
        var second = post("/v1/attendance/mark/bulk", body(
            "schoolId", cbse().id(), "sectionId", sectionId, "onDate", MARK_DATE,
            "markedByStaffId", cbse().teacherStaffIds().get(1),
            "entries", List.of(Map.of("studentId", students.get(0), "status", "absent"))), token);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(count("SELECT count(*) FROM attendance_record WHERE section_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL", sectionId, MARK_DATE)).isEqualTo(students.size());
        assertThat(queryOne("SELECT status FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL", String.class, students.get(0), MARK_DATE)).isEqualTo("absent");
        assertThat(queryOne("SELECT marked_by_staff_id FROM attendance_record WHERE student_id = ? "
            + "AND on_date = ?::date AND period_no IS NULL", UUID.class, students.get(0), MARK_DATE))
            .isEqualTo(cbse().teacherStaffIds().get(0));
    }

    @Test @Tag("P1")
    void cert_ATT_02_periodAndDayLevelRecordsCoexistForTheSameDate() {
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = firstStudentIn(sectionId);
        // The class teacher takes the day; the subject teacher timetabled for
        // period 4 takes the period — each marking what is theirs to mark.
        String classTeacher = teacherToken(cbse(), 0);
        String token = tokenForTeacherOfPeriod(sectionId, 1, 4);

        post("/v1/attendance/mark", body("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionId, "onDate", MARK_DATE, "status", "present"), classTeacher);
        var periodMark = post("/v1/attendance/mark", body("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionId, "onDate", MARK_DATE, "periodNo", 4, "status", "absent"), token);
        assertThat(periodMark.getStatusCode()).isEqualTo(HttpStatus.OK);

        var forStudent = get("/v1/attendance/students/" + studentId
            + "?from=" + MARK_DATE + "&to=" + MARK_DATE, token).getBody();
        assertThat(forStudent).hasSize(2);
        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no = 4", studentId, MARK_DATE)).isEqualTo(1);
    }

    @Test @Tag("P1")
    void cert_ATT_03_absenceNotifiesTheParentWithoutDuplicating() {
        String token = teacherToken(cbse(), 0);
        UUID sectionId = currentFocusSection(cbse());
        // A student and a day no other scenario marks, so the register is this
        // test's alone and the dispatch count means what it says.
        UUID studentId = studentsIn(sectionId).get(8);
        String onDate = "2026-08-11";
        String dedupeKey = "attendance:absent:" + studentId + ":" + onDate;

        long recipients = count("SELECT count(*) FROM guardian_student gs JOIN guardian g "
            + "ON g.id = gs.guardian_id WHERE gs.student_id = ? AND gs.is_communications_recipient "
            + "AND g.opt_in_email AND g.email IS NOT NULL", studentId);
        assertThat(recipients).isGreaterThan(0);

        // A child who is present is not news.
        assertThat(markStatus(token, sectionId, studentId, onDate, "present").getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(absenceDispatches(dedupeKey)).isZero();

        // The correction to absent is, and it goes out on the spot rather than
        // in a nightly batch: a parent who is told at 16:00 learns nothing.
        java.time.Instant beforeMark = java.time.Instant.now();
        assertThat(markStatus(token, sectionId, studentId, onDate, "absent").getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(absenceDispatches(dedupeKey)).isEqualTo(recipients);

        assertThat(count("SELECT count(*) FROM notification_dispatch WHERE dedupe_key = ? "
            + "AND status = 'sent' AND sent_at IS NOT NULL", dedupeKey)).isEqualTo(recipients);
        long slowest = count("SELECT coalesce(max(extract(epoch FROM (sent_at - ?::timestamptz)) * 1000), 0)::bigint "
            + "FROM notification_dispatch WHERE dedupe_key = ?", java.sql.Timestamp.from(beforeMark), dedupeKey);
        assertThat(slowest).isLessThan(30_000);

        assertThat(queryOne("SELECT template_code FROM notification_dispatch WHERE dedupe_key = ? LIMIT 1",
            String.class, dedupeKey)).isEqualTo("attendance_absent");
        assertThat(queryOne("SELECT variables::text FROM notification_dispatch WHERE dedupe_key = ? LIMIT 1",
            String.class, dedupeKey)).contains(onDate);

        // Correcting it back and forth is one absence as far as the family is
        // concerned. The suppression is on the event, not on the write, so a
        // re-saved register sends nothing however it moved in between.
        markStatus(token, sectionId, studentId, onDate, "present");
        markStatus(token, sectionId, studentId, onDate, "absent");
        markStatus(token, sectionId, studentId, onDate, "absent");
        assertThat(absenceDispatches(dedupeKey)).isEqualTo(recipients);

        // A different day is a different absence, and does get its own message.
        String nextDay = "2026-08-12";
        markStatus(token, sectionId, studentId, nextDay, "absent");
        assertThat(absenceDispatches("attendance:absent:" + studentId + ":" + nextDay)).isEqualTo(recipients);
    }

    private org.springframework.http.ResponseEntity<com.fasterxml.jackson.databind.JsonNode> markStatus(
            String token, UUID sectionId, UUID studentId, String onDate, String status) {
        return post("/v1/attendance/mark", body("schoolId", cbse().id(), "studentId", studentId,
            "sectionId", sectionId, "onDate", onDate, "status", status,
            "markedByStaffId", cbse().teacherStaffIds().get(0)), token);
    }

    private long absenceDispatches(String dedupeKey) {
        return count("SELECT count(*) FROM notification_dispatch WHERE dedupe_key = ?", dedupeKey);
    }

    @Test @Tag("P1")
    void cert_ATT_04_statusesComputeIntoTheMonthlyPercentage() {
        String token = teacherToken(cbse(), 0);
        UUID sectionId = currentFocusSection(cbse());
        // A student no other scenario marks, so the week is this test's alone.
        UUID studentId = studentsIn(sectionId).get(5);

        Map<String, String> week = Map.of(
            "2026-08-03", "present",
            "2026-08-04", "late",
            "2026-08-05", "half_day",
            "2026-08-06", "excused",
            "2026-08-07", "absent");
        week.forEach((date, status) -> {
            var marked = post("/v1/attendance/mark", body(
                "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
                "onDate", date, "status", status, "markedByStaffId", cbse().teacherStaffIds().get(0)), token);
            assertThat(marked.getStatusCode()).isEqualTo(HttpStatus.OK);
        });

        var summary = get("/v1/attendance/students/" + studentId
            + "/summary?from=2026-08-03&to=2026-08-07", token);
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = summary.getBody();

        assertThat(body.get("workingDays").asInt()).isEqualTo(5);
        // Excused leaves the denominator rather than counting as an absence.
        assertThat(body.get("consideredDays").asInt()).isEqualTo(4);
        assertThat(body.get("present").asInt()).isEqualTo(1);
        assertThat(body.get("late").asInt()).isEqualTo(1);
        assertThat(body.get("halfDay").asInt()).isEqualTo(1);
        assertThat(body.get("excused").asInt()).isEqualTo(1);
        assertThat(body.get("absent").asInt()).isEqualTo(1);
        // Present + late (present, just not on time) + half a day, over four days.
        assertThat(body.get("percentage").asDouble()).isEqualTo(62.5);
    }

    @Test @Tag("P1")
    void cert_ATT_05_approvedLeaveMaterialisesAttendance() {
        String principal = principalToken(cbse());
        String teacher = teacherToken(cbse(), 0);
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = studentsIn(sectionId).get(4);

        // A day the teacher had already marked, and three days still to come:
        // approval has to cope with both, because a family applies for leave
        // after the fact as often as before it.
        String markedDay = "2026-08-06";
        assertThat(post("/v1/attendance/mark", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", markedDay, "status", "absent",
            "markedByStaffId", cbse().teacherStaffIds().get(0)), teacher).getStatusCode())
            .isEqualTo(HttpStatus.OK);

        UUID leaveId = applyLeave(studentId, markedDay, markedDay, "Fever", principal);
        UUID futureLeaveId = applyLeave(studentId, "2026-08-17", "2026-08-19", "Family wedding", principal);

        // Pending leave changes nothing — an application is a request.
        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? "
            + "AND on_date BETWEEN '2026-08-17' AND '2026-08-19'", studentId)).isZero();

        approveLeave(leaveId, principal);
        approveLeave(futureLeaveId, principal);

        // The three future working days are written; Saturday and Sunday are not.
        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? AND status = 'leave' "
            + "AND source = 'auto' AND leave_application_id = ?", studentId, futureLeaveId)).isEqualTo(3);

        // A second approval over the same days is refused: it would take the
        // days over, and withdrawing it would then delete days the first
        // approval still covers.
        UUID overlappingLeaveId = applyLeave(studentId, "2026-08-18", "2026-08-18", "Clinic visit", principal);
        assertThat(post("/v1/attendance/leave/" + overlappingLeaveId + "/decide", body(
            "status", "approved", "approverStaffId", cbse().principalStaffId()), principal)
            .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? AND status = 'leave' "
            + "AND leave_application_id = ?", studentId, futureLeaveId)).isEqualTo(3);

        // The marked day is amended rather than silently overwritten: the
        // teacher's 'absent' is still recoverable.
        assertThat(queryOne("SELECT status FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL", String.class, studentId, markedDay)).isEqualTo("leave");
        assertThat(queryOne("SELECT old_status FROM attendance_amendment WHERE student_id = ? "
            + "AND on_date = ?::date", String.class, studentId, markedDay)).isEqualTo("absent");

        var summary = get("/v1/attendance/students/" + studentId
            + "/summary?from=2026-08-17&to=2026-08-19", principal).getBody();
        assertThat(summary.get("workingDays").asInt()).isEqualTo(3);
        assertThat(summary.get("onLeave").asInt()).isEqualTo(3);
        // Approved leave leaves the denominator rather than counting against the child.
        assertThat(summary.get("consideredDays").asInt()).isZero();

        // Revoking the approval unwinds exactly what it created, and restores
        // what it changed.
        assertThat(post("/v1/attendance/leave/" + futureLeaveId + "/decide", body(
            "status", "rejected", "approverStaffId", cbse().principalStaffId()), principal)
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/v1/attendance/leave/" + leaveId + "/decide", body(
            "status", "rejected", "approverStaffId", cbse().principalStaffId()), principal)
            .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? "
            + "AND on_date BETWEEN '2026-08-17' AND '2026-08-19'", studentId)).isZero();
        assertThat(queryOne("SELECT status FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL", String.class, studentId, markedDay)).isEqualTo("absent");

        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM attendance_amendment WHERE student_id = ?", studentId);
            jdbc.update("DELETE FROM attendance_record WHERE student_id = ? AND on_date = ?::date",
                studentId, markedDay);
            jdbc.update("DELETE FROM leave_application WHERE id IN (?, ?, ?)", leaveId, futureLeaveId,
                overlappingLeaveId);
        });
    }

    @Test @Tag("P1")
    void cert_ATT_06_correctionAfterLockRequiresAnApprovedAuditedAmendment() {
        String teacher = teacherToken(cbse(), 0);
        String principal = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = studentsIn(sectionId).get(3);
        String onDate = "2026-08-05";

        assertThat(post("/v1/attendance/mark", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", onDate, "status", "absent",
            "markedByStaffId", cbse().teacherStaffIds().get(0)), teacher).getStatusCode())
            .isEqualTo(HttpStatus.OK);

        // Age the mark past the school's 24-hour marking window: the register
        // has been read by then, so it stops being a teacher's to overwrite.
        inChainDo(jdbc -> jdbc.update(
            "UPDATE attendance_record SET marked_at = now() - interval '3 days' "
            + "WHERE student_id = ? AND on_date = ?::date AND period_no IS NULL", studentId, onDate));

        var overwrite = post("/v1/attendance/mark", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", onDate, "status", "present"), teacher);
        assertThat(overwrite.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(overwrite.getBody().get("message").asText()).contains("amendment");
        assertThat(queryOne("SELECT status FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL", String.class, studentId, onDate)).isEqualTo("absent");

        var requested = post("/v1/attendance/amendments", body(
            "studentId", studentId, "onDate", onDate, "newStatus", "present",
            "reason", "Parent produced the medical certificate; child was in school"), teacher);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID amendmentId = UUID.fromString(requested.getBody().get("id").asText());
        assertThat(requested.getBody().get("oldStatus").asText()).isEqualTo("absent");
        assertThat(requested.getBody().get("status").asText()).isEqualTo("pending");

        // The register is untouched until somebody decides.
        assertThat(queryOne("SELECT status FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL", String.class, studentId, onDate)).isEqualTo("absent");

        // Neither the person who raised it nor a teacher without an approving
        // role can wave it through.
        assertThat(post("/v1/attendance/amendments/" + amendmentId + "/decide",
            body("status", "approved", "reason", "Mine to fix"), teacher).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/v1/attendance/amendments/" + amendmentId + "/decide",
            body("status", "approved", "reason", "Looks fine"), teacherToken(cbse(), 1)).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        var decided = post("/v1/attendance/amendments/" + amendmentId + "/decide",
            body("status", "approved", "reason", "Certificate seen and filed"), principal);
        assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decided.getBody().get("status").asText()).isEqualTo("approved");

        assertThat(queryOne("SELECT status FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL", String.class, studentId, onDate)).isEqualTo("present");
        // The prior value survives the correction.
        assertThat(queryOne("SELECT old_status FROM attendance_amendment WHERE id = ?",
            String.class, amendmentId)).isEqualTo("absent");

        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'attendance.amendment_decided' "
            + "AND target_id = ? AND actor_user_id = ? AND reason = ? "
            + "AND before_state IS NOT NULL AND after_state IS NOT NULL",
            amendmentId, cbse().principalUserId(), "Certificate seen and filed")).isEqualTo(1);

        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM attendance_amendment WHERE id = ?", amendmentId);
            jdbc.update("DELETE FROM attendance_record WHERE student_id = ? AND on_date = ?::date",
                studentId, onDate);
        });
    }

    @Test @Tag("P1")
    void cert_ATT_07_deviceEventsAreIdempotentPerStudentAndDay() {
        String token = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = studentsIn(sectionId).get(1);
        UUID deviceId = registerDevice("CERT-BIO-" + UUID.randomUUID().toString().substring(0, 6));
        String onDate = "2026-08-04";

        for (int i = 0; i < 3; i++) {
            var ingested = post("/v1/devices/" + deviceId + "/events/student", body(
                "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
                "onDate", onDate, "source", "biometric"), token);
            assertThat(ingested.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL", studentId, onDate)).isEqualTo(1);
        // The device's own date is honoured — the server never re-derives it from a receive time.
        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? "
            + "AND on_date = ?::date + 1", studentId, onDate)).isZero();
    }

    @Test @Tag("P1")
    void cert_ATT_08_offlineDeviceBacklogDoesNotOverwriteManualCorrections() {
        String principal = principalToken(cbse());
        String teacher = teacherToken(cbse(), 0);
        UUID sectionId = sectionOf(cbse(), cbse().currentAy().code(), cbse().focusGradeCode(), "B");
        UUID student = createStudent("ATT08-" + UUID.randomUUID().toString().substring(0, 6));
        enrol(student, sectionId, "2026-07-01", null, principal);
        UUID deviceId = registerDevice("CERT-ATT08-" + UUID.randomUUID().toString().substring(0, 6));

        try {
            // The gate was offline from Monday to Thursday, and the school carried
            // on without it. Monday: the teacher marked the child absent.
            assertThat(markStatus(teacher, sectionId, student, "2026-08-17", "absent").getStatusCode())
                .isEqualTo(HttpStatus.OK);
            // Tuesday: nobody marked anything.
            // Wednesday: an approved leave put the day down as leave.
            approveLeave(applyLeave(student, "2026-08-19", "2026-08-19", "Family function", principal), principal);
            assertThat(statusOn(student, "2026-08-19")).isEqualTo("leave");
            // Thursday: marked absent, and long since signed off.
            markStatus(teacher, sectionId, student, "2026-08-20", "absent");
            inChainDo(jdbc -> jdbc.update("UPDATE attendance_record SET marked_at = now() - interval '3 days' "
                + "WHERE student_id = ? AND on_date = '2026-08-20'", student));
            String mondayMarkedAt = markedAt(student, "2026-08-17");

            // The device comes back and replays the four days it held. Every
            // event is accepted — a backlog that errors is one the bridge retries
            // forever — whatever it finds already on the register.
            for (String day : List.of("2026-08-17", "2026-08-18", "2026-08-19", "2026-08-20")) {
                assertThat(gateRead(deviceId, student, sectionId, day, "biometric", principal).getStatusCode())
                    .as("replay of " + day).isEqualTo(HttpStatus.OK);
            }

            // The empty day is filled, on the day the card was read rather than
            // the day the replay arrived.
            assertThat(statusOn(student, "2026-08-18")).isEqualTo("present");
            assertThat(sourceOn(student, "2026-08-18")).isEqualTo("biometric");

            // What a person decided stands: the teacher's mark, the approved
            // leave, and the register that was signed off.
            assertThat(statusOn(student, "2026-08-17")).isEqualTo("absent");
            assertThat(sourceOn(student, "2026-08-17")).isEqualTo("manual");
            assertThat(statusOn(student, "2026-08-19")).isEqualTo("leave");
            assertThat(statusOn(student, "2026-08-20")).isEqualTo("absent");
            // And a replay does not reopen a register for editing by touching it.
            assertThat(markedAt(student, "2026-08-17")).isEqualTo(mondayMarkedAt);

            // The punch is not thrown away either. A card read at the gate on a
            // day the register says absent is a disagreement somebody should
            // see, so the record carries it.
            var week = get("/v1/attendance/students/" + student + "?from=2026-08-17&to=2026-08-20", principal)
                .getBody();
            assertThat(week).hasSize(4);
            for (var day : week) {
                assertThat(day.get("gateSeenAt").asText()).as("gate read on " + day.get("onDate").asText())
                    .isNotBlank();
                assertThat(day.get("gateSource").asText()).isEqualTo("biometric");
            }

            // And the school can ask for those disagreements across a range:
            // the three days a person said "not here" and the gate said
            // otherwise — not Tuesday, where the gate is the only witness.
            var disagreements = get("/v1/attendance/gate-disagreements?schoolId=" + cbse().id()
                + "&from=2026-08-17&to=2026-08-21&sectionId=" + sectionId, principal);
            assertThat(disagreements.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<String> days = new java.util.ArrayList<>();
            for (var row : disagreements.getBody()) {
                if (row.get("studentId").asText().equals(student.toString())) {
                    days.add(row.get("onDate").asText() + " " + row.get("status").asText());
                    assertThat(row.get("studentName").asText()).isEqualTo("Certification Candidate");
                    assertThat(row.get("gateSource").asText()).isEqualTo("biometric");
                }
            }
            assertThat(days).containsExactlyInAnyOrder(
                "2026-08-17 absent", "2026-08-19 leave", "2026-08-20 absent");
            // A family's token gets nowhere near it.
            assertThat(get("/v1/attendance/gate-disagreements?schoolId=" + cbse().id()
                + "&from=2026-08-17&to=2026-08-21", guardianTokenFor(cbse(), firstStudentIn(currentFocusSection(cbse()))))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // Replaying the backlog a second time changes nothing.
            gateRead(deviceId, student, sectionId, "2026-08-17", "biometric", principal);
            assertThat(statusOn(student, "2026-08-17")).isEqualTo("absent");
            assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? "
                + "AND on_date BETWEEN '2026-08-17' AND '2026-08-20'", student)).isEqualTo(4);

            // The precedence runs one way. A teacher may still correct what the
            // gate wrote, and the next replay leaves that correction alone too.
            assertThat(markStatus(teacher, sectionId, student, "2026-08-18", "late").getStatusCode())
                .isEqualTo(HttpStatus.OK);
            gateRead(deviceId, student, sectionId, "2026-08-18", "rfid", principal);
            assertThat(statusOn(student, "2026-08-18")).isEqualTo("late");
            assertThat(sourceOn(student, "2026-08-18")).isEqualTo("manual");

            // A bridge does not get to call its event a person's mark.
            assertThat(gateRead(deviceId, student, sectionId, "2026-08-21", "manual", principal).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? "
                + "AND on_date = '2026-08-21'", student)).isZero();
        } finally {
            inChainDo(jdbc -> {
                jdbc.update("DELETE FROM attendance_amendment WHERE student_id = ?", student);
                jdbc.update("DELETE FROM attendance_record WHERE student_id = ?", student);
                jdbc.update("DELETE FROM leave_application WHERE subject_id = ?", student);
                jdbc.update("DELETE FROM enrolment WHERE student_id = ?", student);
                jdbc.update("DELETE FROM student WHERE id = ?", student);
                jdbc.update("DELETE FROM device WHERE id = ?", deviceId);
            });
        }
    }

    private org.springframework.http.ResponseEntity<com.fasterxml.jackson.databind.JsonNode> gateRead(
            UUID deviceId, UUID studentId, UUID sectionId, String onDate, String source, String token) {
        return post("/v1/devices/" + deviceId + "/events/student", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", onDate, "source", source), token);
    }

    private String statusOn(UUID studentId, String onDate) {
        return queryOne("SELECT status FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL AND voided_at IS NULL", String.class, studentId, onDate);
    }

    private String sourceOn(UUID studentId, String onDate) {
        return queryOne("SELECT source FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL AND voided_at IS NULL", String.class, studentId, onDate);
    }

    private String markedAt(UUID studentId, String onDate) {
        return queryOne("SELECT marked_at::text FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no IS NULL AND voided_at IS NULL", String.class, studentId, onDate);
    }

    @Test @Tag("P2")
    void cert_ATT_09_offlineTeacherMarkingSurfacesConflicts() {
        String principal = principalToken(cbse());
        String teacher = teacherToken(cbse(), 0);
        UUID sectionId = sectionOf(cbse(), cbse().currentAy().code(), cbse().focusGradeCode(), "B");
        String onDate = "2026-08-24";
        String tag = "ATT09-" + UUID.randomUUID().toString().substring(0, 6);
        UUID onLeave = createStudent(tag + "-1");      // the office records a leave while the teacher is offline
        UUID marked = createStudent(tag + "-2");       // the principal marks them late in the meantime
        UUID atGate = createStudent(tag + "-3");       // only the gate sees them in the meantime
        UUID untouched = createStudent(tag + "-4");    // nobody else touches this one
        UUID agreed = createStudent(tag + "-5");       // the teacher and the record already agree
        List<UUID> students = List.of(onLeave, marked, atGate, untouched, agreed);
        students.forEach(st -> enrol(st, sectionId, "2026-07-01", null, principal));
        UUID deviceId = registerDevice("CERT-ATT09-" + UUID.randomUUID().toString().substring(0, 6));
        String register = "/v1/attendance?sectionId=" + sectionId + "&onDate=" + onDate;

        try {
            // First thing, with signal: two marks go in the ordinary way.
            markStatus(teacher, sectionId, onLeave, onDate, "absent");
            markStatus(teacher, sectionId, agreed, onDate, "present");

            // The teacher opens the register. What it shows is what they will
            // be marking against once the signal drops.
            java.util.Map<String, String> seen = new java.util.HashMap<>();
            get(register, teacher).getBody()
                .forEach(r -> seen.put(r.get("studentId").asText(), r.get("markedAt").asText()));
            assertThat(seen).containsKeys(onLeave.toString(), agreed.toString())
                .doesNotContainKey(marked.toString());
            String agreedMarkedAt = markedAt(agreed, onDate);

            // Offline. Meanwhile the school carries on.
            approveLeave(applyLeave(onLeave, onDate, onDate, "Hospital appointment", principal), principal);
            markStatus(principal, sectionId, marked, onDate, "late");
            gateRead(deviceId, atGate, sectionId, onDate, "biometric", principal);

            // Back in range, the phone sends what the teacher marked — each
            // mark carrying what the teacher was looking at when they made it.
            var synced = post("/v1/attendance/mark/sync", body(
                "schoolId", cbse().id(), "sectionId", sectionId, "onDate", onDate,
                "entries", List.of(
                    syncEntry(onLeave, "present", seen.get(onLeave.toString())),
                    syncEntry(marked, "absent", null),
                    syncEntry(atGate, "absent", null),
                    syncEntry(untouched, "present", null),
                    syncEntry(agreed, "present", seen.get(agreed.toString())))), teacher);

            // The request succeeds: one disagreement does not cost the register.
            assertThat(synced.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(synced.getBody().get("applied").findValuesAsText("studentId"))
                .containsExactlyInAnyOrder(atGate.toString(), untouched.toString(), agreed.toString());

            // What somebody else decided in the meantime is surfaced, both
            // values side by side, and is still what the register says.
            var conflicts = synced.getBody().get("conflicts");
            assertThat(conflicts).hasSize(2);
            for (var conflict : conflicts) {
                assertThat(conflict.get("kind").asText()).isEqualTo("changed");
                boolean leave = conflict.get("studentId").asText().equals(onLeave.toString());
                assertThat(conflict.get("yours").asText()).isEqualTo(leave ? "present" : "absent");
                assertThat(conflict.get("theirs").get("status").asText()).isEqualTo(leave ? "leave" : "late");
            }
            assertThat(statusOn(onLeave, onDate)).isEqualTo("leave");
            assertThat(statusOn(marked, onDate)).isEqualTo("late");

            // A gate read is not a decision: the teacher's mark replaces it,
            // and the punch stays on the record as the disagreement it now is.
            assertThat(statusOn(atGate, onDate)).isEqualTo("absent");
            assertThat(sourceOn(atGate, onDate)).isEqualTo("manual");
            assertThat(statusOn(untouched, onDate)).isEqualTo("present");
            // Agreeing with a record does not rewrite it.
            assertThat(markedAt(agreed, onDate)).isEqualTo(agreedMarkedAt);

            // The teacher looks at the two and overrules one: the same mark,
            // now sent against the value they have been shown.
            String theirs = "";
            for (var conflict : conflicts) {
                if (conflict.get("studentId").asText().equals(marked.toString())) {
                    theirs = conflict.get("theirs").get("markedAt").asText();
                }
            }
            var overruled = post("/v1/attendance/mark/sync", body(
                "schoolId", cbse().id(), "sectionId", sectionId, "onDate", onDate,
                "entries", List.of(syncEntry(marked, "absent", theirs))), teacher);
            assertThat(overruled.getBody().get("conflicts")).isEmpty();
            assertThat(statusOn(marked, onDate)).isEqualTo("absent");

            // The stale view does not get a second go: sent again from what the
            // phone originally held, it conflicts again.
            var stale = post("/v1/attendance/mark/sync", body(
                "schoolId", cbse().id(), "sectionId", sectionId, "onDate", onDate,
                "entries", List.of(syncEntry(marked, "present", null))), teacher);
            assertThat(stale.getBody().get("conflicts")).hasSize(1);
            assertThat(statusOn(marked, onDate)).isEqualTo("absent");

            // A register that has been signed off refuses the mark for that
            // child, says so, and still takes the others in the same send.
            inChainDo(jdbc -> jdbc.update("UPDATE attendance_record SET marked_at = now() - interval '3 days' "
                + "WHERE student_id = ? AND on_date = ?::date", untouched, onDate));
            java.util.Map<String, String> fresh = new java.util.HashMap<>();
            get(register, teacher).getBody()
                .forEach(r -> fresh.put(r.get("studentId").asText(), r.get("markedAt").asText()));
            var mixed = post("/v1/attendance/mark/sync", body(
                "schoolId", cbse().id(), "sectionId", sectionId, "onDate", onDate,
                "entries", List.of(
                    syncEntry(untouched, "absent", fresh.get(untouched.toString())),
                    syncEntry(atGate, "late", fresh.get(atGate.toString())))), teacher);
            assertThat(mixed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(mixed.getBody().get("applied").findValuesAsText("studentId"))
                .containsExactly(atGate.toString());
            assertThat(mixed.getBody().get("conflicts")).hasSize(1);
            assertThat(mixed.getBody().get("conflicts").get(0).get("kind").asText()).isEqualTo("refused");
            assertThat(mixed.getBody().get("conflicts").get(0).get("message").asText()).contains("amendment");
            assertThat(statusOn(untouched, onDate)).isEqualTo("present");

            // Not a door for a family.
            assertThat(post("/v1/attendance/mark/sync", body(
                "schoolId", cbse().id(), "sectionId", sectionId, "onDate", onDate,
                "entries", List.of(syncEntry(untouched, "present", null))),
                guardianTokenFor(cbse(), firstStudentIn(currentFocusSection(cbse())))).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            inChainDo(jdbc -> {
                for (UUID st : students) {
                    jdbc.update("DELETE FROM attendance_amendment WHERE student_id = ?", st);
                    jdbc.update("DELETE FROM attendance_record WHERE student_id = ?", st);
                    jdbc.update("DELETE FROM leave_application WHERE subject_id = ?", st);
                    jdbc.update("DELETE FROM enrolment WHERE student_id = ?", st);
                    jdbc.update("DELETE FROM student WHERE id = ?", st);
                }
                jdbc.update("DELETE FROM device WHERE id = ?", deviceId);
            });
        }
    }

    private static java.util.Map<String, Object> syncEntry(UUID studentId, String status, String seenMarkedAt) {
        java.util.Map<String, Object> entry = new java.util.HashMap<>();
        entry.put("studentId", studentId);
        entry.put("status", status);
        if (seenMarkedAt != null) entry.put("seenMarkedAt", seenMarkedAt);
        return entry;
    }

    @Test @Tag("P1")
    void cert_ATT_10_percentageUsesTheEnrolmentWindowAsDenominator() {
        String token = principalToken(cbse());
        // Section B, not the focus section: these two enrolments would otherwise
        // join the roster every other attendance scenario marks.
        UUID sectionId = sectionOf(cbse(), cbse().currentAy().code(), cbse().focusGradeCode(), "B");

        // Joined on 15 July: the 13 working days from then to month end are the
        // whole of their denominator, not July's 23.
        UUID joiner = createStudent("ATT10-JOIN-" + UUID.randomUUID().toString().substring(0, 6));
        enrol(joiner, sectionId, "2026-07-15", null, token);

        var joinerSummary = get("/v1/attendance/students/" + joiner
            + "/summary?from=2026-07-01&to=2026-07-31", token);
        assertThat(joinerSummary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(joinerSummary.getBody().get("workingDays").asInt()).isEqualTo(13);
        assertThat(joinerSummary.getBody().get("enrolledFrom").asText()).isEqualTo("2026-07-15");

        // Left on 15 July: the 11 working days up to then.
        UUID leaver = createStudent("ATT10-LEFT-" + UUID.randomUUID().toString().substring(0, 6));
        enrol(leaver, sectionId, "2026-07-01", "2026-07-15", token);

        var leaverSummary = get("/v1/attendance/students/" + leaver
            + "/summary?from=2026-07-01&to=2026-07-31", token);
        assertThat(leaverSummary.getBody().get("workingDays").asInt()).isEqualTo(11);
        assertThat(leaverSummary.getBody().get("enrolledTo").asText()).isEqualTo("2026-07-15");

        // A full-year student over the same month is measured against all 23.
        var fullYear = get("/v1/attendance/students/" + firstStudentIn(currentFocusSection(cbse()))
            + "/summary?from=2026-07-01&to=2026-07-31", token);
        assertThat(fullYear.getBody().get("workingDays").asInt()).isEqualTo(23);

        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM enrolment WHERE student_id IN (?, ?)", joiner, leaver);
            jdbc.update("DELETE FROM student WHERE id IN (?, ?)", joiner, leaver);
        });
    }

    @Test @Tag("P2")
    @Disabled("No chronic-absence report: nothing aggregates attendance over a rolling window per section "
        + "or grade. New gap found in Phase 0.")
    void cert_ATT_11_chronicAbsenceReportMatchesRawRecords() {
    }

    @Test @Tag("P1")
    void cert_ATT_12_futureOrOutOfWindowDatesAreRefused() {
        String token = teacherToken(cbse(), 0);
        UUID sectionId = currentFocusSection(cbse());
        UUID studentId = studentsIn(sectionId).get(7);
        String futureDate = java.time.LocalDate.now().plusDays(7).toString();

        var future = post("/v1/attendance/mark", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", futureDate, "status", "present"), token);
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(future.getBody().get("message").asText()).contains("future date");

        // The student's enrolment in this section starts with the current AY, so
        // a date from the previous year is outside their window here.
        var beforeEnrolment = post("/v1/attendance/mark", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", "2026-03-03", "status", "present"), token);
        assertThat(beforeEnrolment.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(beforeEnrolment.getBody().get("message").asText()).contains("not enrolled");

        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? "
            + "AND on_date IN (?::date, '2026-03-03')", studentId, futureDate)).isZero();
    }

    @Test @Tag("P2")
    void cert_ATT_13_staffAttendanceReflectsApprovedLeave() {
        String principal = principalToken(cbse());
        UUID staffId = cbse().teacherStaffIds().get(7);
        String onDate = "2026-08-05";

        UUID leaveId = applyStaffLeave(staffId, onDate, onDate, "Medical", principal);
        assertThat(count("SELECT count(*) FROM staff_attendance WHERE staff_id = ? AND on_date = ?::date",
            staffId, onDate)).isZero();

        approveLeave(leaveId, principal);

        assertThat(queryOne("SELECT status FROM staff_attendance WHERE staff_id = ? AND on_date = ?::date",
            String.class, staffId, onDate)).isEqualTo("leave");
        assertThat(queryOne("SELECT leave_application_id FROM staff_attendance WHERE staff_id = ? "
            + "AND on_date = ?::date", UUID.class, staffId, onDate)).isEqualTo(leaveId);

        // Withdrawing the approval takes the day back out of the register.
        assertThat(post("/v1/attendance/leave/" + leaveId + "/decide", body(
            "status", "cancelled", "approverStaffId", cbse().principalStaffId()), principal)
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM staff_attendance WHERE staff_id = ? AND on_date = ?::date",
            staffId, onDate)).isZero();

        inChainDo(jdbc -> jdbc.update("DELETE FROM leave_application WHERE id = ?", leaveId));
    }

    // ---------------------------------------------------------------- helpers

    private UUID createStudent(String admissionNo) {
        var created = post("/v1/people/students", Map.of(
            "schoolId", cbse().id(), "admissionNo", admissionNo,
            "firstName", "Certification", "lastName", "Candidate",
            "dob", "2015-05-05", "gender", "male"), principalToken(cbse()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }

    /** Enrols, then closes the window when {@code endsOn} is given. */
    private void enrol(UUID studentId, UUID sectionId, String startsOn, String endsOn, String token) {
        var enrolled = post("/v1/enrolment", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "academicYearId", cbse().currentAy().id(), "startsOn", startsOn), token);
        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.OK);
        if (endsOn != null) {
            UUID enrolmentId = UUID.fromString(enrolled.getBody().get("id").asText());
            var closed = post("/v1/enrolment/" + enrolmentId + "/status",
                body("status", "withdrawn", "endsOn", endsOn, "reason", "Certification fixture leaver"), token);
            assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    /** A token for whoever is timetabled to teach that period of that weekday. */
    private String tokenForTeacherOfPeriod(UUID sectionId, int dayOfWeek, int periodNo) {
        UUID staffId = queryOne("SELECT teacher_staff_id FROM timetable_slot WHERE section_id = ? "
            + "AND day_of_week = ? AND period_no = ?", UUID.class, sectionId, dayOfWeek, periodNo);
        int index = cbse().teacherStaffIds().indexOf(staffId);
        assertThat(index).as("period %s is taught by a seeded teacher", periodNo).isNotNegative();
        return teacherToken(cbse(), index);
    }

    private UUID applyLeave(UUID studentId, String from, String to, String reason, String token) {
        var applied = post("/v1/attendance/leave", body(
            "schoolId", cbse().id(), "subjectType", "student", "subjectId", studentId,
            "fromDate", from, "toDate", to, "reason", reason), token);
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(applied.getBody().get("id").asText());
    }

    private UUID applyStaffLeave(UUID staffId, String from, String to, String reason, String token) {
        var applied = post("/v1/attendance/leave", body(
            "schoolId", cbse().id(), "subjectType", "staff", "subjectId", staffId,
            "fromDate", from, "toDate", to, "reason", reason), token);
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(applied.getBody().get("id").asText());
    }

    private void approveLeave(UUID leaveId, String approverToken) {
        var decided = post("/v1/attendance/leave/" + leaveId + "/decide", body(
            "status", "approved", "approverStaffId", cbse().principalStaffId()), approverToken);
        assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UUID registerDevice(String serialNo) {
        var created = post("/v1/devices", body("schoolId", cbse().id(), "kind", "biometric",
            "vendor", "eSSL", "model", "K30", "serialNo", serialNo,
            "location", "Main gate", "apiKey", "cert-device-key"), principalToken(cbse()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(created.getBody().get("id").asText());
    }
}
