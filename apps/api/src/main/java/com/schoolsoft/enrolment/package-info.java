/**
 * Enrolment (per design doc §7 Layer 1.4). A student's time-bounded
 * participation in a section. A student may have many historical enrolments
 * (transfers, promotions, re-admission) but only one {@code active} enrolment
 * at a time (DB-enforced via a partial unique index on {@code student_id}).
 */
package com.schoolsoft.enrolment;
