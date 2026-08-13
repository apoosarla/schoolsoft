package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** CERT-TT — timetable. */
class TimetableCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    void cert_TT_01_bellScheduleMasterDrivesSlotCreation() {
        String token = principalToken(cie());
        UUID gradeId = gradeOf(cie(), cie().focusGradeCode());
        UUID sectionId = currentFocusSection(cie());

        var schedule = post("/v1/timetable/bell-schedules", body(
            "schoolId", cie().id(), "code", "SENIOR-TT01", "name", "Senior school day",
            "effectiveFrom", cie().currentAy().startsOn().toString(),
            "gradeIds", List.of(gradeId),
            "periods", List.of(
                Map.of("periodNo", 1, "label", "Period 1", "startsAt", "08:00:00", "endsAt", "08:45:00",
                       "isBreak", false),
                Map.of("periodNo", 2, "label", "Period 2", "startsAt", "08:45:00", "endsAt", "09:30:00",
                       "isBreak", false),
                Map.of("periodNo", 3, "label", "Short break", "startsAt", "09:30:00", "endsAt", "09:50:00",
                       "isBreak", true))), token);
        assertThat(schedule.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID scheduleId = UUID.fromString(schedule.getBody().get("id").asText());
        UUID period2 = UUID.fromString(schedule.getBody().get("periods").get(1).get("id").asText());
        UUID breakPeriod = UUID.fromString(schedule.getBody().get("periods").get(2).get("id").asText());

        UUID slotId = null;
        try {
            // The section reads its day from the master, through its grade.
            var forSection = get("/v1/timetable/sections/" + sectionId + "/bell-schedule", token);
            assertThat(forSection.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(forSection.getBody().get("code").asText()).isEqualTo("SENIOR-TT01");

            // A slot built against a period takes its times from the schedule —
            // the caller does not get to disagree with the bell.
            var slot = post("/v1/timetable/slots", body(
                "sectionId", sectionId, "subjectId", subjectOf(cie(), cie().subjectCodes().get(0)),
                "teacherStaffId", cie().teacherStaffIds().get(2), "dayOfWeek", 5,
                "periodId", period2, "room", "TT01-1",
                "effectiveFrom", cie().currentAy().startsOn().toString()), token);
            assertThat(slot.getStatusCode()).isEqualTo(HttpStatus.OK);
            slotId = UUID.fromString(slot.getBody().get("id").asText());
            assertThat(slot.getBody().get("startsAt").asText()).startsWith("08:45");
            assertThat(slot.getBody().get("endsAt").asText()).startsWith("09:30");
            assertThat(slot.getBody().get("periodNo").asInt()).isEqualTo(2);

            // Nothing can be timetabled into a break.
            var intoBreak = post("/v1/timetable/slots", body(
                "sectionId", sectionId, "subjectId", subjectOf(cie(), cie().subjectCodes().get(0)),
                "teacherStaffId", cie().teacherStaffIds().get(3), "dayOfWeek", 5,
                "periodId", breakPeriod, "room", "TT01-2",
                "effectiveFrom", cie().currentAy().startsOn().toString()), token);
            assertThat(intoBreak.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(intoBreak.getBody().get("message").asText()).contains("break");

            // Overlapping periods are refused when the master is authored.
            var overlapping = post("/v1/timetable/bell-schedules", body(
                "schoolId", cie().id(), "code", "BAD-TT01", "name", "Overlapping day",
                "effectiveFrom", cie().currentAy().startsOn().toString(),
                "periods", List.of(
                    Map.of("periodNo", 1, "label", "P1", "startsAt", "08:00:00", "endsAt", "09:00:00",
                           "isBreak", false),
                    Map.of("periodNo", 2, "label", "P2", "startsAt", "08:30:00", "endsAt", "09:30:00",
                           "isBreak", false))), token);
            assertThat(overlapping.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            UUID createdSlot = slotId;
            inChainDo(jdbc -> {
                if (createdSlot != null) jdbc.update("DELETE FROM timetable_slot WHERE id = ?", createdSlot);
                jdbc.update("DELETE FROM grade_bell_schedule WHERE bell_schedule_id = ?", scheduleId);
                jdbc.update("DELETE FROM bell_period WHERE bell_schedule_id = ?", scheduleId);
                jdbc.update("DELETE FROM bell_schedule WHERE id = ?", scheduleId);
                jdbc.update("DELETE FROM bell_schedule WHERE code = 'BAD-TT01'");
            });
        }
    }

    @Test @Tag("P1")
    void cert_TT_02_slotThatDoubleBooksATeacherIsRejected() {
        String token = principalToken(cbse());
        UUID sectionB = sectionOf(cbse(), cbse().currentAy().code(), "6", "B");
        UUID teacher = cbse().teacherStaffIds().get(3);

        var first = post("/v1/timetable/slots", body(
            "sectionId", sectionB, "subjectId", subjectOf(cbse(), "MATH"), "teacherStaffId", teacher,
            "dayOfWeek", 3, "periodNo", 7, "startsAt", "15:00:00", "endsAt", "15:45:00",
            "room", "R201", "effectiveFrom", "2026-04-01"), token);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID slotId = UUID.fromString(first.getBody().get("id").asText());

        try {
            // Same teacher, same day, overlapping window, different section.
            var clash = post("/v1/timetable/slots", body(
                "sectionId", sectionOf(cbse(), cbse().currentAy().code(), "7", "B"),
                "subjectId", subjectOf(cbse(), "MATH"), "teacherStaffId", teacher,
                "dayOfWeek", 3, "periodNo", 7, "startsAt", "15:15:00", "endsAt", "16:00:00",
                "room", "R202", "effectiveFrom", "2026-04-01"), token);
            assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(clash.getBody().get("message").asText()).contains("overlapping");
        } finally {
            delete("/v1/timetable/slots/" + slotId, token);
        }
    }

    @Test @Tag("P2")
    void cert_TT_03_slotThatDoubleBooksARoomIsRejected() {
        String token = principalToken(cbse());
        UUID sectionA = currentFocusSection(cbse());
        UUID sectionB = sectionOf(cbse(), cbse().currentAy().code(), cbse().focusGradeCode(), "B");
        String room = "LAB-TT03";

        var first = post("/v1/timetable/slots", body(
            "sectionId", sectionA, "subjectId", subjectOf(cbse(), cbse().subjectCodes().get(0)),
            "teacherStaffId", cbse().teacherStaffIds().get(2), "dayOfWeek", 6, "periodNo", 8,
            "startsAt", "15:00:00", "endsAt", "15:45:00", "room", room,
            "effectiveFrom", cbse().currentAy().startsOn().toString()), token);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        try {
            // Different section, different teacher, same room and time.
            var clash = post("/v1/timetable/slots", body(
                "sectionId", sectionB, "subjectId", subjectOf(cbse(), cbse().subjectCodes().get(1)),
                "teacherStaffId", cbse().teacherStaffIds().get(3), "dayOfWeek", 6, "periodNo", 8,
                "startsAt", "15:15:00", "endsAt", "16:00:00", "room", room,
                "effectiveFrom", cbse().currentAy().startsOn().toString()), token);
            assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(clash.getBody().get("message").asText()).contains("already booked");

            // A different room at the same time is fine.
            var elsewhere = post("/v1/timetable/slots", body(
                "sectionId", sectionB, "subjectId", subjectOf(cbse(), cbse().subjectCodes().get(1)),
                "teacherStaffId", cbse().teacherStaffIds().get(3), "dayOfWeek", 6, "periodNo", 8,
                "startsAt", "15:15:00", "endsAt", "16:00:00", "room", room + "-2",
                "effectiveFrom", cbse().currentAy().startsOn().toString()), token);
            assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            inChainDo(jdbc -> jdbc.update("DELETE FROM timetable_slot WHERE room LIKE 'LAB-TT03%'"));
        }
    }

    @Test @Tag("P3")
    void cert_TT_04_teacherWeeklyLoadOverMaximumWarnsAtPublish() {
        String token = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());
        UUID teacherId = cbse().teacherStaffIds().get(0);

        long load = count("SELECT count(*) FROM timetable_slot WHERE teacher_staff_id = ?", teacherId);
        assertThat(load).isGreaterThan(0);

        // A ceiling the existing timetable already breaches.
        inChainDo(jdbc -> jdbc.update("UPDATE staff SET max_weekly_periods = ? WHERE id = ?",
            (int) load - 1, teacherId));
        try {
            var warned = get("/v1/timetable/sections/" + sectionId + "/publish-warnings", token);
            assertThat(warned.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(warned.getBody().get("warnings").toString()).contains("over their maximum");
            // A warning, not a refusal: the school publishes knowing what is wrong.
            assertThat(warned.getBody().get("publishable").asBoolean()).isTrue();

            // Raised above the load, the warning goes away.
            inChainDo(jdbc -> jdbc.update("UPDATE staff SET max_weekly_periods = ? WHERE id = ?",
                (int) load + 5, teacherId));
            var quiet = get("/v1/timetable/sections/" + sectionId + "/publish-warnings", token);
            assertThat(quiet.getBody().get("warnings").toString()).doesNotContain("over their maximum");
        } finally {
            inChainDo(jdbc -> jdbc.update("UPDATE staff SET max_weekly_periods = NULL WHERE id = ?", teacherId));
        }
    }

    @Test @Tag("P1")
    @Disabled("Slots carry effective_from/effective_to, but every read ignores them: "
        + "TimetableRepository.forSection/forTeacher select all rows for the section, so a revision "
        + "rewrites history instead of superseding it from a date. New gap found in Phase 0.")
    void cert_TT_05_midYearRevisionResolvesAgainstTheTimetableInForceOnADate() {
    }

    @Test @Tag("P1")
    void cert_TT_06_teacherPersonalTimetableMatchesTheirSectionSlots() {
        String token = teacherToken(cbse(), 0);
        UUID teacherStaffId = cbse().teacherStaffIds().get(0);

        JsonNode personal = get("/v1/timetable/teachers/" + teacherStaffId, token).getBody();
        assertThat(personal).isNotEmpty();

        Set<String> fromPersonalView = new HashSet<>();
        personal.forEach(slot -> fromPersonalView.add(slot.get("id").asText()));

        Set<String> fromSections = new HashSet<>();
        for (String sectionId : queryList(
                "SELECT DISTINCT section_id::text FROM timetable_slot WHERE teacher_staff_id = ?",
                String.class, teacherStaffId)) {
            get("/v1/timetable/sections/" + sectionId, token).getBody().forEach(slot -> {
                if (slot.get("teacherStaffId").asText().equals(teacherStaffId.toString())) {
                    fromSections.add(slot.get("id").asText());
                }
            });
        }
        assertThat(fromPersonalView).isEqualTo(fromSections);
    }

    @Test @Tag("P2")
    @Disabled("No day view: the timetable API returns the full week for a section with no date filter and "
        + "no after-hours suppression, so a parent/student 'today' view cannot be served. New gap found "
        + "in Phase 0.")
    void cert_TT_07_parentViewShowsTodaysPeriodsOnly() {
    }

    @Test @Tag("P1")
    void cert_TT_08_teacherAbsenceAssignsASubstituteWhoCanMarkAttendance() {
        String principal = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());
        String onDate = "2026-08-04";                       // a Tuesday already past
        int periodNo = 3;

        UUID slotId = queryOne("SELECT id FROM timetable_slot WHERE section_id = ? AND day_of_week = 2 "
            + "AND period_no = ?", UUID.class, sectionId, periodNo);
        UUID absentStaffId = queryOne("SELECT teacher_staff_id FROM timetable_slot WHERE id = ?",
            UUID.class, slotId);

        var applied = post("/v1/attendance/leave", body(
            "schoolId", cbse().id(), "subjectType", "staff", "subjectId", absentStaffId,
            "fromDate", onDate, "toDate", onDate, "reason", "Unwell"), principal);
        UUID leaveId = UUID.fromString(applied.getBody().get("id").asText());
        assertThat(post("/v1/attendance/leave/" + leaveId + "/decide",
            body("status", "approved", "approverStaffId", cbse().principalStaffId()), principal)
            .getStatusCode()).isEqualTo(HttpStatus.OK);

        // Somebody free that period, who is neither the class teacher nor a
        // teacher of this section — so the only thing that can authorise them
        // is the cover itself.
        int substituteIndex = substituteIndexFor(sectionId, absentStaffId);
        UUID substituteStaffId = cbse().teacherStaffIds().get(substituteIndex);
        String substitute = teacherToken(cbse(), substituteIndex);
        UUID studentId = studentsIn(sectionId).get(2);

        var refused = post("/v1/attendance/mark", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", onDate, "periodNo", periodNo, "status", "present"), substitute);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var assigned = post("/v1/timetable/cover", body(
            "slotId", slotId, "onDate", onDate, "substituteStaffId", substituteStaffId,
            "reason", "Covering for approved leave"), principal);
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID coverId = UUID.fromString(assigned.getBody().get("id").asText());
        assertThat(assigned.getBody().get("absentStaffId").asText()).isEqualTo(absentStaffId.toString());
        assertThat(assigned.getBody().get("leaveApplicationId").asText()).isEqualTo(leaveId.toString());

        // The substitute sees the period in their own day.
        var substituteDay = get("/v1/timetable/teachers/" + substituteStaffId + "/day?date=" + onDate,
            substitute);
        assertThat(substituteDay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(substituteDay.getBody().get("covering")).hasSize(1);
        assertThat(substituteDay.getBody().get("covering").get(0).get("slotId").asText())
            .isEqualTo(slotId.toString());

        // The absent teacher no longer does.
        var absentDay = get("/v1/timetable/teachers/" + absentStaffId + "/day?date=" + onDate, principal);
        absentDay.getBody().get("slots").forEach(slot ->
            assertThat(slot.get("id").asText()).isNotEqualTo(slotId.toString()));
        assertThat(absentDay.getBody().get("coveredForThem")).hasSize(1);

        // And the section is told who is walking through the door.
        var sectionDay = get("/v1/timetable/sections/" + sectionId + "/day?date=" + onDate, principal);
        assertThat(sectionDay.getBody().get("covers")).hasSize(1);
        assertThat(sectionDay.getBody().get("covers").get(0).get("substituteStaffId").asText())
            .isEqualTo(substituteStaffId.toString());

        // The cover is what authorises the register.
        var marked = post("/v1/attendance/mark", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", onDate, "periodNo", periodNo, "status", "present",
            "markedByStaffId", substituteStaffId), substitute);
        assertThat(marked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
            + "AND period_no = ?", studentId, onDate, periodNo)).isEqualTo(1);

        // Cancelling it takes the authority away again.
        assertThat(delete("/v1/timetable/cover/" + coverId, principal).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(post("/v1/attendance/mark", body(
            "schoolId", cbse().id(), "studentId", studentId, "sectionId", sectionId,
            "onDate", onDate, "periodNo", periodNo, "status", "absent"), substitute).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        inChainDo(jdbc -> {
            jdbc.update("DELETE FROM attendance_record WHERE student_id = ? AND on_date = ?::date "
                + "AND period_no = ?", studentId, java.sql.Date.valueOf(onDate), periodNo);
            jdbc.update("DELETE FROM timetable_cover WHERE slot_id = ?", slotId);
            jdbc.update("DELETE FROM staff_attendance WHERE leave_application_id = ?", leaveId);
            jdbc.update("DELETE FROM leave_application WHERE id = ?", leaveId);
        });
    }

    /**
     * A teacher who can only be authorised by the cover: not the absent one,
     * not the class teacher (teacher 1 holds that role school-wide), and not
     * timetabled against this section that day.
     */
    private int substituteIndexFor(UUID sectionId, UUID absentStaffId) {
        for (int i = 1; i < cbse().teacherStaffIds().size(); i++) {
            UUID candidate = cbse().teacherStaffIds().get(i);
            if (candidate.equals(absentStaffId)) continue;
            long ownSlots = count("SELECT count(*) FROM timetable_slot WHERE teacher_staff_id = ? "
                + "AND section_id = ? AND day_of_week = 2", candidate, sectionId);
            long primary = count("SELECT count(*) FROM section_subject_teacher WHERE section_id = ? "
                + "AND teacher_staff_id = ? AND is_primary", sectionId, candidate);
            if (ownSlots == 0 && primary == 0) return i;
        }
        throw new IllegalStateException("The fixture has no teacher free of this section on Tuesdays");
    }

    @Test @Tag("P1")
    void cert_TT_09_examWeekReplacesTheRegularTimetable() {
        String token = principalToken(cbse());
        UUID sectionId = currentFocusSection(cbse());
        UUID gradeId = gradeOf(cbse(), cbse().focusGradeCode());
        String examDate = "2026-09-15";                      // a Tuesday inside Term 1

        // Before the exam week, the section's Tuesday is an ordinary teaching day.
        var ordinary = get("/v1/timetable/sections/" + sectionId + "/day?date=" + examDate, token);
        assertThat(ordinary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ordinary.getBody().get("working").asBoolean()).isTrue();
        assertThat(ordinary.getBody().get("examDay").asBoolean()).isFalse();
        assertThat(ordinary.getBody().get("slots").size()).isGreaterThan(0);

        UUID scheduleId = UUID.fromString(post("/v1/exams/schedules", body(
            "schoolId", cbse().id(), "academicYearId", cbse().currentAy().id(),
            "termId", termOf(cbse(), cbse().currentAy().code(), "T1"),
            "code", "CERT-TT09", "name", "Term 1 examinations",
            "startsOn", examDate, "endsOn", "2026-09-18"), token).getBody().get("id").asText());
        try {
            post("/v1/exams/schedules/" + scheduleId + "/sessions", body(
                "gradeId", gradeId, "subjectId", subjectOf(cbse(), "ENG"), "paperCode", "P1",
                "name", "English Paper 1", "onDate", examDate,
                "startsAt", "09:30:00", "endsAt", "11:30:00", "room", "Hall A",
                "invigilatorStaffId", cbse().teacherStaffIds().get(4), "maxMarks", 80.0), token);

            // A draft schedule must not blank anybody's day — the exams officer
            // is still moving papers around.
            assertThat(get("/v1/timetable/sections/" + sectionId + "/day?date=" + examDate, token)
                .getBody().get("examDay").asBoolean()).isFalse();

            assertThat(post("/v1/exams/schedules/" + scheduleId + "/publish", null, token).getStatusCode())
                .isEqualTo(HttpStatus.OK);

            var examDay = get("/v1/timetable/sections/" + sectionId + "/day?date=" + examDate, token).getBody();
            assertThat(examDay.get("examDay").asBoolean()).isTrue();
            assertThat(examDay.get("calendarKind").asText()).isEqualTo("exam_day");
            // The regular periods are suppressed rather than shown alongside:
            // the class is not going to its lessons that morning.
            assertThat(examDay.get("slots")).isEmpty();
            assertThat(examDay.get("examSessions")).hasSize(1);
            assertThat(examDay.get("examSessions").get(0).get("name").asText()).isEqualTo("English Paper 1");
            assertThat(examDay.get("examSessions").get(0).get("room").asText()).isEqualTo("Hall A");
            assertThat(examDay.get("reason").asText()).contains("Exam day");

            // A Tuesday outside the exam week is untouched.
            assertThat(get("/v1/timetable/sections/" + sectionId + "/day?date=2026-09-29", token)
                .getBody().get("slots").size()).isGreaterThan(0);
        } finally {
            inChainDo(jdbc -> jdbc.update("DELETE FROM exam_schedule WHERE id = ?", scheduleId));
        }
    }

    @Test @Tag("P2")
    @Disabled("No section-delete endpoint exists, so the blocked-or-cascade behaviour has no surface to "
        + "certify. New gap found in Phase 0.")
    void cert_TT_10_deletingASectionWithALiveTimetableIsBlockedOrCascadesCleanly() {
    }
}
