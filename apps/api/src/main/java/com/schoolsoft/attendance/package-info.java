/**
 * Attendance (per design doc §7 Layer 1.5). Daily and period-wise, sourced
 * from manual entry, biometric, or RFID gate events (source discriminator on
 * {@code attendance_record}). Marking is an upsert on
 * {@code (student_id, on_date, period_no)} so re-marking corrects rather than
 * duplicates. Leave applications are shared between students and staff via
 * the {@code subject_type} discriminator on {@code leave_application}.
 */
package com.schoolsoft.attendance;
