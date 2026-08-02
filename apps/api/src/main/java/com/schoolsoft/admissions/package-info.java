/**
 * Admissions funnel (per design doc §13): enquiry -> application -> documents
 * -> fee -> review -> test -> offer -> accept -> seat-confirm -> auto-enrol.
 * Each transition is a state on {@code admission_application.state} and is
 * recorded as an immutable {@code admission_event} row. Enrolling converts
 * the application into a {@code student} + active {@code enrolment} row.
 */
package com.schoolsoft.admissions;
