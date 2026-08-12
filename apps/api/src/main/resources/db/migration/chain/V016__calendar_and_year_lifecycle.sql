-- ============================================================================
-- Phase 1 — temporal foundation. Closes GAP-01 (no calendar / working-day
-- master), GAP-25 (no AY/term date validation) and the schema half of GAP-14
-- (no closed-year lock).
--
-- Everything that computes over dates depends on these tables: attendance
-- denominators, timetable suppression, closure handling, fee due dates, and
-- (later) the rollover readiness check.
-- ============================================================================

-- `EXCLUDE USING gist` below needs the btree operator classes for the plain
-- equality leg (school_id). Extensions are database-scoped, so this is a no-op
-- for every chain schema after the first.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ----------------------------------------------------------------------------
-- Working-day pattern: which weekdays a school teaches on, effective-dated so a
-- school moving from a 6-day to a 5-day week keeps last year's denominators.
--
-- weekday_mask is a 7-char string indexed by ISO day-of-week (Mon=0 .. Sun=6),
-- '1' = working. A text mask rather than a bitmask keeps it readable in psql,
-- which is where a wrong denominator gets diagnosed.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS working_day_pattern (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    campus_id       UUID REFERENCES campus(id) ON DELETE CASCADE,  -- NULL = whole school
    effective_from  DATE NOT NULL,
    effective_to    DATE,
    weekday_mask    TEXT NOT NULL DEFAULT '1111110'
                      CHECK (weekday_mask ~ '^[01]{7}$'),
    -- Alternate-Saturday rule for schools that teach on some Saturdays only.
    -- 'all' / 'none' / 'odd' / 'even' — odd/even count Saturdays within the
    -- calendar month (1st Saturday = 1).
    saturday_rule   TEXT NOT NULL DEFAULT 'all'
                      CHECK (saturday_rule IN ('all','none','odd','even')),
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX IF NOT EXISTS wdp_school_effective_idx
    ON working_day_pattern (school_id, effective_from DESC);

-- ----------------------------------------------------------------------------
-- Calendar: one row per exceptional date. The pattern says what a normal week
-- looks like; this says where reality differs.
--
-- grade_id / campus_id scope an entry to a cohort or a campus (a board-exam
-- week where only Grades 11-12 attend; a campus-only local holiday).
-- declared_by / declared_at exist because an unplanned same-day closure is an
-- audit event, not just a date.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS school_calendar (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    academic_year_id UUID REFERENCES academic_year(id) ON DELETE CASCADE,
    on_date         DATE NOT NULL,
    kind            TEXT NOT NULL
                      CHECK (kind IN ('holiday','vacation','working_saturday','closure','exam_day')),
    title           TEXT NOT NULL,
    description     TEXT,
    grade_id        UUID REFERENCES grade(id) ON DELETE CASCADE,   -- NULL = all grades
    campus_id       UUID REFERENCES campus(id) ON DELETE CASCADE,  -- NULL = all campuses
    source          TEXT NOT NULL DEFAULT 'manual'
                      CHECK (source IN ('manual','gazetted_import','system')),
    declared_by_staff_id UUID REFERENCES staff(id),
    declared_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One entry per (date, kind) within a scope. Re-importing the same gazetted
    -- holiday list is then a no-op rather than a pile of duplicates.
    -- NULLS NOT DISTINCT is the whole point: a school-wide entry has NULL grade
    -- and NULL campus, and under the default semantics two of those would not
    -- conflict, so the import would duplicate exactly the common case.
    UNIQUE NULLS NOT DISTINCT (school_id, on_date, kind, grade_id, campus_id)
);
CREATE INDEX IF NOT EXISTS school_calendar_lookup_idx
    ON school_calendar (school_id, on_date);

-- ----------------------------------------------------------------------------
-- Academic year lifecycle (GAP-14). `is_current` stays as the fast lookup the
-- rest of the codebase already reads; `status` is what governs mutability.
--
--   planning : structure being built (rollover clones into this state)
--   active   : the year schools operate in
--   closed   : read-only; attendance, marks and fees refuse writes
-- ----------------------------------------------------------------------------
ALTER TABLE academic_year
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'active'
        CHECK (status IN ('planning','active','closed')),
    ADD COLUMN IF NOT EXISTS closed_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS closed_by_staff_id UUID REFERENCES staff(id),
    ADD COLUMN IF NOT EXISTS reopened_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reopened_by_staff_id UUID REFERENCES staff(id),
    ADD COLUMN IF NOT EXISTS reopen_reason    TEXT;

-- Only a current year may be current, and a closed year never is.
ALTER TABLE academic_year
    DROP CONSTRAINT IF EXISTS academic_year_closed_not_current;
ALTER TABLE academic_year
    ADD CONSTRAINT academic_year_closed_not_current
        CHECK (NOT (is_current AND status = 'closed'));

-- Two academic years of one school may not overlap in time (GAP-25). Written as
-- an exclusion constraint rather than a trigger so a concurrent insert cannot
-- slip between the check and the write.
ALTER TABLE academic_year
    DROP CONSTRAINT IF EXISTS academic_year_no_overlap;
ALTER TABLE academic_year
    ADD CONSTRAINT academic_year_no_overlap
        EXCLUDE USING gist (
            school_id WITH =,
            daterange(starts_on, ends_on, '[]') WITH &&
        );

-- ----------------------------------------------------------------------------
-- Terms sit inside their academic year and do not overlap each other (GAP-25).
-- term has no school_id, so the AY-window check needs the parent row: enforced
-- by trigger, with the non-overlap rule as an exclusion constraint on the AY.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION term_within_academic_year() RETURNS TRIGGER AS $$
DECLARE
  ay_start DATE;
  ay_end   DATE;
BEGIN
  SELECT starts_on, ends_on INTO ay_start, ay_end
    FROM academic_year WHERE id = NEW.academic_year_id;
  IF NEW.starts_on < ay_start OR NEW.ends_on > ay_end THEN
    RAISE EXCEPTION 'Term % (% .. %) falls outside its academic year (% .. %)',
      NEW.code, NEW.starts_on, NEW.ends_on, ay_start, ay_end
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.ends_on < NEW.starts_on THEN
    RAISE EXCEPTION 'Term % ends before it starts', NEW.code
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS term_within_academic_year_trg ON term;
CREATE TRIGGER term_within_academic_year_trg
    BEFORE INSERT OR UPDATE ON term
    FOR EACH ROW EXECUTE FUNCTION term_within_academic_year();

ALTER TABLE term
    DROP CONSTRAINT IF EXISTS term_no_overlap;
ALTER TABLE term
    ADD CONSTRAINT term_no_overlap
        EXCLUDE USING gist (
            academic_year_id WITH =,
            daterange(starts_on, ends_on, '[]') WITH &&
        );

-- ----------------------------------------------------------------------------
-- Voiding attendance (CAL-04). A same-day closure must not delete the marks a
-- teacher already took — the school needs to be able to show what was recorded
-- and that it was voided, by whom, and why.
-- ----------------------------------------------------------------------------
ALTER TABLE attendance_record
    ADD COLUMN IF NOT EXISTS voided_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS void_reason TEXT,
    ADD COLUMN IF NOT EXISTS voided_by_staff_id UUID REFERENCES staff(id);

-- Both uniqueness rules must ignore voided rows, so a day can be re-marked
-- after a closure is withdrawn. The day-level one comes from V010; the
-- period-level one was a table constraint and becomes a partial index for the
-- same reason (a voided period mark must not block a fresh one).
DROP INDEX IF EXISTS attendance_one_day_level_per_student;
CREATE UNIQUE INDEX IF NOT EXISTS attendance_one_day_level_per_student
    ON attendance_record (student_id, on_date)
    WHERE period_no IS NULL AND voided_at IS NULL;

ALTER TABLE attendance_record
    DROP CONSTRAINT IF EXISTS attendance_record_student_id_on_date_period_no_key;
CREATE UNIQUE INDEX IF NOT EXISTS attendance_one_period_level_per_student
    ON attendance_record (student_id, on_date, period_no)
    WHERE period_no IS NOT NULL AND voided_at IS NULL;
