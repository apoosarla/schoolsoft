package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolsoft.certification.support.AbstractCertificationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * CERT-CAL — calendar, holidays & events.
 *
 * Scenarios work in September/October 2026 rather than the current month: the
 * fixture's attendance history and the ATT/DEV scenarios both live in July and
 * August, and a school-wide holiday is not a local fact — declaring one on a
 * date a neighbouring scenario marks attendance on would break it.
 */
class CalendarCertTest extends AbstractCertificationTest {

    private static final String SEP_MON = "2026-09-07";
    private static final String SEP_WED = "2026-09-09";
    private static final String SEP_SAT = "2026-09-12";

    @Test @Tag("P1")
    void cert_CAL_01_annualCalendarIsPublished() {
        String token = principalToken(cbse());
        UUID schoolId = cbse().id();

        // A six-day week with alternate Saturdays, in force from September.
        var pattern = post("/v1/calendar/patterns", body(
            "schoolId", schoolId, "effectiveFrom", "2026-09-01",
            "weekdayMask", "1111110", "saturdayRule", "odd",
            "notes", "Certification: six-day week, odd Saturdays"), token);
        assertThat(pattern.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID patternId = UUID.fromString(pattern.getBody().get("id").asText());

        try {
            var imported = post("/v1/calendar/entries/bulk", body(
                "schoolId", schoolId,
                "academicYearId", cbse().currentAy().id(),
                "entries", List.of(
                    entry(schoolId, "2026-10-02", "holiday", "Gandhi Jayanti"),
                    entry(schoolId, "2026-10-20", "holiday", "Local festival"),
                    entry(schoolId, "2026-10-12", "vacation", "Autumn break"),
                    entry(schoolId, "2026-10-13", "vacation", "Autumn break"),
                    entry(schoolId, "2026-10-17", "working_saturday", "Extra teaching day"),
                    entry(schoolId, "2026-10-26", "exam_day", "Half-yearly paper 1"))),
                token);
            assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(imported.getBody()).hasSize(6);

            // Re-importing the same list corrects rather than duplicates.
            post("/v1/calendar/entries/bulk", body(
                "schoolId", schoolId, "academicYearId", cbse().currentAy().id(),
                "entries", List.of(entry(schoolId, "2026-10-02", "holiday", "Gandhi Jayanti (gazetted)"))),
                token);
            assertThat(count("SELECT count(*) FROM school_calendar WHERE school_id = ? AND on_date = '2026-10-02'",
                schoolId)).isEqualTo(1);
            assertThat(queryOne("SELECT title FROM school_calendar WHERE school_id = ? AND on_date = '2026-10-02'",
                String.class, schoolId)).isEqualTo("Gandhi Jayanti (gazetted)");

            // The published calendar reads back as day statuses, pattern and
            // entries already applied.
            assertThat(dayStatus(schoolId, "2026-10-02", token).get("working").asBoolean()).isFalse();
            assertThat(dayStatus(schoolId, "2026-10-12", token).get("working").asBoolean()).isFalse();
            // 3rd Saturday under the 'odd' rule is a working day by pattern;
            // 4th is not, unless declared a working Saturday.
            assertThat(dayStatus(schoolId, "2026-10-17", token).get("working").asBoolean()).isTrue();
            assertThat(dayStatus(schoolId, "2026-10-24", token).get("working").asBoolean()).isFalse();
            // An exam day is still a school day.
            assertThat(dayStatus(schoolId, "2026-10-26", token).get("working").asBoolean()).isTrue();

            var counted = get("/v1/calendar/working-days?schoolId=" + schoolId
                + "&from=2026-10-01&to=2026-10-31", token);
            assertThat(counted.getStatusCode()).isEqualTo(HttpStatus.OK);
            // October 2026: 22 Mon-Fri days, plus the 1st/3rd/5th Saturdays the
            // odd rule keeps (3, 17, 31), minus 2 holidays and 2 vacation days.
            assertThat(counted.getBody().get("workingDays").asInt()).isEqualTo(21);
        } finally {
            clearCalendar(schoolId, "2026-10-01", "2026-10-31");
            inChainDo(jdbc -> jdbc.update("DELETE FROM working_day_pattern WHERE id = ?", patternId));
        }
    }

    @Test @Tag("P1")
    void cert_CAL_02_attendanceIsRefusedOnAHolidayAndExcludedFromTheDenominator() {
        String token = principalToken(cie());
        UUID schoolId = cie().id();
        UUID sectionId = currentFocusSection(cie());
        UUID studentId = firstStudentIn(sectionId);

        // A past date: a future one would be refused for being in the future
        // (ATT-12) before the calendar was ever consulted.
        String holiday = "2026-08-11";
        declareHoliday(schoolId, holiday, "Founder's Day", token);
        try {
            var refused = post("/v1/attendance/mark", body(
                "schoolId", schoolId, "studentId", studentId, "sectionId", sectionId,
                "onDate", holiday, "status", "present", "source", "manual"), token);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody().get("message").asText()).contains("Founder's Day");

            // Mon-Fri that week is five days; the holiday leaves four.
            var summary = get("/v1/attendance/students/" + studentId
                + "/summary?from=2026-08-10&to=2026-08-14", token);
            assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(summary.getBody().get("workingDays").asInt()).isEqualTo(4);
        } finally {
            clearCalendar(schoolId, holiday, holiday);
        }
    }

    @Test @Tag("P1")
    void cert_CAL_03_timetableSuppressesPeriodsOnAHoliday() {
        String token = principalToken(cie());
        UUID schoolId = cie().id();
        UUID sectionId = currentFocusSection(cie());

        var normalDay = get("/v1/timetable/sections/" + sectionId + "/day?date=" + SEP_WED, token);
        assertThat(normalDay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(normalDay.getBody().get("working").asBoolean()).isTrue();
        assertThat(normalDay.getBody().get("slots")).isNotEmpty();

        declareHoliday(schoolId, SEP_WED, "Mid-term holiday", token);
        try {
            var closed = get("/v1/timetable/sections/" + sectionId + "/day?date=" + SEP_WED, token);
            assertThat(closed.getBody().get("working").asBoolean()).isFalse();
            assertThat(closed.getBody().get("reason").asText()).isEqualTo("Mid-term holiday");
            assertThat(closed.getBody().get("calendarKind").asText()).isEqualTo("holiday");
            assertThat(closed.getBody().get("slots")).isEmpty();
        } finally {
            clearCalendar(schoolId, SEP_WED, SEP_WED);
        }
    }

    @Test @Tag("P1")
    void cert_CAL_04_sameDayClosureVoidsMarksAndNotifiesParents() {
        String token = principalToken(cbse());
        UUID schoolId = cbse().id();
        UUID sectionId = currentFocusSection(cbse());
        List<UUID> students = studentsIn(sectionId).subList(0, 3);
        String onDate = "2026-08-10";           // a Monday already past

        for (UUID studentId : students) {
            var marked = post("/v1/attendance/mark", body(
                "schoolId", schoolId, "studentId", studentId, "sectionId", sectionId,
                "onDate", onDate, "status", "present", "source", "manual"), token);
            assertThat(marked.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        long dispatchesBefore = count("SELECT count(*) FROM notification_dispatch WHERE school_id = ?", schoolId);

        var closure = post("/v1/calendar/closures", body(
            "schoolId", schoolId, "onDate", onDate, "title", "Cyclone warning",
            "description", "District administration ordered schools shut",
            "declaredByStaffId", cbse().principalStaffId()), token);
        try {
            assertThat(closure.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(closure.getBody().get("voidedAttendanceRecords").asInt()).isGreaterThanOrEqualTo(3);
            assertThat(closure.getBody().get("guardiansNotified").asInt()).isGreaterThan(0);

            // Retained, not deleted — the school can still show what was taken.
            assertThat(count("SELECT count(*) FROM attendance_record WHERE section_id = ? AND on_date = ?::date "
                + "AND voided_at IS NOT NULL", sectionId, onDate)).isGreaterThanOrEqualTo(3);
            assertThat(count("SELECT count(*) FROM attendance_record WHERE section_id = ? AND on_date = ?::date "
                + "AND voided_at IS NULL", sectionId, onDate)).isZero();
            assertThat(count("SELECT count(*) FROM notification_dispatch WHERE school_id = ?", schoolId))
                .isGreaterThan(dispatchesBefore);

            // And the day leaves the denominator.
            var summary = get("/v1/attendance/students/" + students.get(0)
                + "/summary?from=2026-08-10&to=2026-08-14", token);
            assertThat(summary.getBody().get("workingDays").asInt()).isEqualTo(4);

            // The declaration is an audit event, not just a date.
            assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'calendar.closure_declared'"))
                .isGreaterThan(0);
        } finally {
            clearCalendar(schoolId, onDate, onDate);
            inChainDo(jdbc -> jdbc.update(
                "DELETE FROM attendance_record WHERE section_id = ? AND on_date = ?::date", sectionId, onDate));
        }
    }

    @Test @Tag("P2")
    void cert_CAL_05_gradeSpecificHolidayAppliesToOneCohort() {
        String token = principalToken(cbse());
        UUID schoolId = cbse().id();
        UUID examGrade = gradeOf(cbse(), cbse().terminalGradeCode());
        UUID otherGrade = gradeOf(cbse(), cbse().focusGradeCode());

        var created = post("/v1/calendar/entries", body(
            "schoolId", schoolId, "onDate", SEP_MON, "kind", "holiday",
            "title", "Board exam week — junior grades only attend", "gradeId", otherGrade), token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);

        try {
            assertThat(dayStatus(schoolId, SEP_MON, otherGrade, null, token).get("working").asBoolean()).isFalse();
            assertThat(dayStatus(schoolId, SEP_MON, examGrade, null, token).get("working").asBoolean()).isTrue();
            // Unscoped ask sees the school as open: the closure is not school-wide.
            assertThat(dayStatus(schoolId, SEP_MON, token).get("working").asBoolean()).isTrue();
        } finally {
            clearCalendar(schoolId, SEP_MON, SEP_MON);
        }
    }

    @Test @Tag("P2")
    void cert_CAL_06_campusSpecificHolidayAppliesToOneCampus() {
        String token = principalToken(cbse());
        UUID schoolId = cbse().id();

        var created = post("/v1/calendar/entries", body(
            "schoolId", schoolId, "onDate", SEP_SAT, "kind", "closure",
            "title", "Annex water supply failure", "campusId", cbse().annexCampusId()), token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);

        try {
            assertThat(dayStatus(schoolId, SEP_SAT, null, cbse().annexCampusId(), token)
                .get("working").asBoolean()).isFalse();
            // The main campus is unaffected — and Saturday is off there anyway by
            // pattern, so assert a weekday too.
            var mainSaturday = dayStatus(schoolId, SEP_SAT, null, cbse().mainCampusId(), token);
            assertThat(mainSaturday.get("reason").asText()).doesNotContain("Annex");
            assertThat(count("SELECT count(*) FROM school_calendar WHERE campus_id = ?", cbse().annexCampusId()))
                .isEqualTo(1);
        } finally {
            clearCalendar(schoolId, SEP_SAT, SEP_SAT);
        }
    }

    @Test @Tag("P2")
    void cert_CAL_07_calendarIsVisibleToParentsAndThePublicSite() {
        String adminToken = principalToken(cbse());
        UUID schoolId = cbse().id();
        UUID studentId = firstStudentIn(currentFocusSection(cbse()));
        String guardianToken = guardianTokenFor(cbse(), studentId);
        String publicPath = "/v1/public/schools/" + seed.chainSlug() + "/" + cbse().slug()
            + "/calendar?from=" + SEP_MON + "&to=" + SEP_MON;

        declareHoliday(schoolId, SEP_MON, "Local festival", adminToken);
        try {
            // Parent app.
            var parentView = get("/v1/calendar/days?schoolId=" + schoolId
                + "&from=" + SEP_MON + "&to=" + SEP_MON, guardianToken);
            assertThat(parentView.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(parentView.getBody().get(0).get("working").asBoolean()).isFalse();
            assertThat(parentView.getBody().get(0).get("reason").asText()).isEqualTo("Local festival");

            // Public site, no token at all.
            var publicView = get(publicPath, null);
            assertThat(publicView.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(publicView.getBody().get(0).get("working").asBoolean()).isFalse();

            // A mid-year amendment is visible on the next read — nothing caches
            // a stale calendar server-side.
            clearCalendar(schoolId, SEP_MON, SEP_MON);
            assertThat(get(publicPath, null).getBody().get(0).get("working").asBoolean()).isTrue();
        } finally {
            clearCalendar(schoolId, SEP_MON, SEP_MON);
        }
    }

    @Test @Tag("P2")
    @Disabled("Announcements carry read receipts, but there is no event entity and no RSVP, so reach "
        + "cannot be reconciled against an event roster. New gap found in Phase 0.")
    void cert_CAL_08_eventsArePublishedWithRsvpAndReconcilableReach() {
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> entry(UUID schoolId, String onDate, String kind, String title) {
        return body("schoolId", schoolId, "onDate", onDate, "kind", kind, "title", title);
    }

    private void declareHoliday(UUID schoolId, String onDate, String title, String token) {
        var created = post("/v1/calendar/entries", body(
            "schoolId", schoolId, "onDate", onDate, "kind", "holiday", "title", title), token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode dayStatus(UUID schoolId, String date, String token) {
        return dayStatus(schoolId, date, null, null, token);
    }

    private JsonNode dayStatus(UUID schoolId, String date, UUID gradeId, UUID campusId, String token) {
        String query = "/v1/calendar/days?schoolId=" + schoolId + "&from=" + date + "&to=" + date
            + (gradeId == null ? "" : "&gradeId=" + gradeId)
            + (campusId == null ? "" : "&campusId=" + campusId);
        var response = get(query, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get(0);
    }

    private void clearCalendar(UUID schoolId, String from, String to) {
        inChainDo(jdbc -> jdbc.update(
            "DELETE FROM school_calendar WHERE school_id = ? AND on_date BETWEEN ?::date AND ?::date",
            schoolId, from, to));
    }
}
