-- ============================================================================
-- attendance_record's UNIQUE (student_id, on_date, period_no) does not dedupe
-- day-level marks: Postgres treats two NULL period_no values as distinct, so
-- ON CONFLICT never fires for day-level attendance and re-marking a student
-- would insert duplicate rows. Add an explicit partial unique index for the
-- period_no IS NULL case.
-- ============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS attendance_one_day_level_per_student
    ON attendance_record (student_id, on_date) WHERE period_no IS NULL;
