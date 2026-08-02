/**
 * Timetable (per design doc §7 Layer 1.6). Per-section weekly schedule —
 * period/subject/teacher/room slots keyed by day-of-week. Clash detection
 * happens at creation time on the teacher's schedule (a teacher cannot be in
 * two sections at the same day/period).
 */
package com.schoolsoft.timetable;
