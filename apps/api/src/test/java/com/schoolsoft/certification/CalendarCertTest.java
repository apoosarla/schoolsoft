package com.schoolsoft.certification;

import com.schoolsoft.certification.support.AbstractCertificationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CERT-CAL — calendar, holidays & events.
 *
 * The whole area is blocked on GAP-01: there is no calendar table, so there is
 * nothing to publish, nothing to suppress a timetable against, and no
 * working-day denominator. Phase 1 of the remediation plan lands all of it.
 */
class CalendarCertTest extends AbstractCertificationTest {

    @Test @Tag("P1")
    @Disabled("GAP-01 — no school_calendar or working_day_pattern table; the annual calendar cannot be "
        + "published at all (Phase 1).")
    void cert_CAL_01_annualCalendarIsPublished() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-01 — attendance accepts any date, and no percentage anywhere subtracts holidays from "
        + "the denominator (Phase 1).")
    void cert_CAL_02_attendanceIsRefusedOnAHolidayAndExcludedFromTheDenominator() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-01 — the timetable read has no calendar to consult, so a holiday renders as a normal "
        + "school day (Phase 1).")
    void cert_CAL_03_timetableSuppressesPeriodsOnAHoliday() {
    }

    @Test @Tag("P1")
    @Disabled("GAP-01 — no closure declaration, so marked attendance cannot be voided and no parent "
        + "notification is raised (Phase 1).")
    void cert_CAL_04_sameDayClosureVoidsMarksAndNotifiesParents() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-01 — a calendar entry has no grade scope (Phase 1).")
    void cert_CAL_05_gradeSpecificHolidayAppliesToOneCohort() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-01 + GAP-24 — a calendar entry has no campus scope, and campus is decorative "
        + "(Phase 1).")
    void cert_CAL_06_campusSpecificHolidayAppliesToOneCampus() {
    }

    @Test @Tag("P2")
    @Disabled("GAP-01 — no calendar to expose to the parent app or the public site (Phase 1).")
    void cert_CAL_07_calendarIsVisibleToParentsAndThePublicSite() {
    }

    @Test @Tag("P2")
    @Disabled("Announcements carry read receipts, but there is no event entity and no RSVP, so reach "
        + "cannot be reconciled against an event roster. New gap found in Phase 0.")
    void cert_CAL_08_eventsArePublishedWithRsvpAndReconcilableReach() {
    }
}
