package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolsoft.certification.support.AbstractCertificationTest;
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
    @Disabled("GAP-12 — no bell_schedule/period master; every slot carries its own free-text times "
        + "(Phase 2).")
    void cert_TT_01_bellScheduleMasterDrivesSlotCreation() {
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
    @Disabled("GAP-12 — the clash query is teacher-only; two sections can be scheduled into the same room "
        + "at the same time (Phase 2).")
    void cert_TT_03_slotThatDoubleBooksARoomIsRejected() {
    }

    @Test @Tag("P3")
    @Disabled("GAP-12 — no configured weekly teacher load and no publish-time warning (Phase 2).")
    void cert_TT_04_teacherWeeklyLoadOverMaximumWarnsAtPublish() {
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
    @Disabled("GAP-07 — staff absence and timetable slots are unrelated: no cover assignment, no "
        + "substitute view, no permission for the substitute to mark that period (Phase 3).")
    void cert_TT_08_teacherAbsenceAssignsASubstituteWhoCanMarkAttendance() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-06 + GAP-01 — there is no exam timetable entity and no calendar to mark an exam week "
        + "with, so the regular timetable cannot be suppressed (Phases 1 and 5).")
    void cert_TT_09_examWeekReplacesTheRegularTimetable() {
    }

    @Test @Tag("P2")
    @Disabled("No section-delete endpoint exists, so the blocked-or-cascade behaviour has no surface to "
        + "certify. New gap found in Phase 0.")
    void cert_TT_10_deletingASectionWithALiveTimetableIsBlockedOrCascadesCleanly() {
    }
}
