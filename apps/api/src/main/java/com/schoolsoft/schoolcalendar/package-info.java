/**
 * School calendar and working days (per design doc §5; closes GAP-01).
 *
 * Two tables answer two different questions. {@code working_day_pattern} says
 * what a normal week looks like for a school or campus — which weekdays are
 * taught, and which Saturdays — effective-dated, so a school that moves from a
 * six-day to a five-day week does not retroactively change last year's
 * attendance percentages. {@code school_calendar} says where reality departs
 * from that: holidays, vacation blocks, working Saturdays, unplanned closures,
 * exam days, each optionally scoped to one grade or one campus.
 *
 * {@link com.schoolsoft.schoolcalendar.api.WorkingDayService} is the single
 * authority over both. Nothing else in the codebase may compute a working-day
 * denominator or shift a due date itself — attendance percentages, fee due
 * dates, and (later) the rollover readiness check all go through it, which is
 * what makes them agree.
 *
 * Named {@code schoolcalendar} rather than {@code calendar} so it never reads
 * ambiguously against {@code java.util.Calendar} at an import site.
 */
@org.springframework.modulith.ApplicationModule(displayName = "School Calendar")
package com.schoolsoft.schoolcalendar;
