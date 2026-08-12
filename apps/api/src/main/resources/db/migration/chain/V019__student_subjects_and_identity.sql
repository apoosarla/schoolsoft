-- ============================================================================
-- Phase 2 — structural integrity. Closes GAP-05 (no student-level subject
-- election), GAP-26 (no admission/roll number policy), GAP-10 (capacity is
-- decorative) and GAP-12 (no bell schedule, no room clash check).
--
-- These are shape changes: every month of real data on top of the old shape
-- makes them more expensive, which is why they come before the modules that
-- would grow onto them.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Student subject election (GAP-05).
--
-- The resolution rule the whole codebase now shares:
--
--   a student's subject set = the section's compulsory subjects
--                           + that student's own elections
--
-- `section_subject_teacher` keeps saying who teaches what to a section; it
-- gains a flag saying whether that subject is compulsory for everyone in the
-- section or offered as an elective. An IGCSE option block or a Class 11
-- stream is then an elective_group with a pick count, and what a student
-- actually studies is a student_subject row against their enrolment.
-- ----------------------------------------------------------------------------
ALTER TABLE section_subject_teacher
    ADD COLUMN IF NOT EXISTS is_elective BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS elective_group (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    academic_year_id UUID NOT NULL REFERENCES academic_year(id) ON DELETE CASCADE,
    grade_id        UUID NOT NULL REFERENCES grade(id) ON DELETE CASCADE,
    code            TEXT NOT NULL,                    -- 'BLOCK-A' | 'STREAM'
    name            TEXT NOT NULL,
    -- How many of the group's subjects a student takes. min = max for a
    -- straight "pick one of these"; they differ for "pick two or three".
    min_picks       INT NOT NULL DEFAULT 1 CHECK (min_picks >= 0),
    max_picks       INT NOT NULL DEFAULT 1 CHECK (max_picks >= 1),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (max_picks >= min_picks),
    UNIQUE (school_id, academic_year_id, grade_id, code)
);

CREATE TABLE IF NOT EXISTS elective_group_option (
    elective_group_id UUID NOT NULL REFERENCES elective_group(id) ON DELETE CASCADE,
    subject_id        UUID NOT NULL REFERENCES subject(id) ON DELETE CASCADE,
    -- Seats for this option across the grade; NULL = uncapped.
    capacity          INT,
    PRIMARY KEY (elective_group_id, subject_id)
);

CREATE TABLE IF NOT EXISTS student_subject (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    enrolment_id    UUID NOT NULL REFERENCES enrolment(id) ON DELETE CASCADE,
    subject_id      UUID NOT NULL REFERENCES subject(id) ON DELETE CASCADE,
    elective_group_id UUID REFERENCES elective_group(id) ON DELETE SET NULL,
    status          TEXT NOT NULL DEFAULT 'elected'
                      CHECK (status IN ('elected','dropped')),
    -- Elections are effective-dated: a student who changes options in
    -- September keeps the marks earned under the old one.
    effective_from  DATE NOT NULL,
    effective_to    DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

-- One live election per subject per enrolment; a dropped one may be re-taken.
CREATE UNIQUE INDEX IF NOT EXISTS student_subject_one_live_per_subject
    ON student_subject (enrolment_id, subject_id)
    WHERE status = 'elected';
CREATE INDEX IF NOT EXISTS student_subject_enrolment_idx ON student_subject (enrolment_id);
CREATE INDEX IF NOT EXISTS student_subject_subject_idx   ON student_subject (subject_id);

-- ----------------------------------------------------------------------------
-- Number series (GAP-26). One generator for every human-facing number, so
-- admission numbers, roll numbers, invoice and receipt numbers and certificate
-- serials stop being invented at each call site.
--
-- pattern is a template: {YY} / {YYYY} calendar year, {AY} the academic year
-- code, {SEQ} the counter (pad with {SEQ:n}). scope_id narrows the counter —
-- roll numbers run per section, invoices per school.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS number_series (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    kind            TEXT NOT NULL
                      CHECK (kind IN ('admission','roll','invoice','receipt','certificate')),
    scope_id        UUID,                             -- NULL = school-wide
    pattern         TEXT NOT NULL,
    next_value      BIGINT NOT NULL DEFAULT 1 CHECK (next_value >= 1),
    reset_policy    TEXT NOT NULL DEFAULT 'never'
                      CHECK (reset_policy IN ('never','yearly')),
    last_reset_year INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (school_id, kind, scope_id)
);

-- Every school starts with a school-wide admission series; roll series are
-- created per section on first use.
INSERT INTO number_series (school_id, kind, scope_id, pattern, next_value, reset_policy)
SELECT s.id, 'admission', NULL, 'ADM{YY}{SEQ:4}',
       -- Start past whatever the school already issued, so a generated number
       -- cannot collide with a hand-keyed one.
       COALESCE((SELECT count(*) FROM student st WHERE st.school_id = s.id), 0) + 1,
       'yearly'
FROM school s
ON CONFLICT DO NOTHING;

-- ----------------------------------------------------------------------------
-- Roll numbers are unique within a section (GAP-26).
--
-- Existing free-text roll numbers may already collide. This refuses to migrate
-- rather than renumbering someone's child silently: the collisions are listed
-- in the error, and an operator resolves them before re-running. There is no
-- correct automatic answer — which of the two Ravis keeps roll 12 is a
-- school's decision, not a migration's.
-- ----------------------------------------------------------------------------
DO $$
DECLARE
  collisions TEXT;
BEGIN
  SELECT string_agg(detail, '; ') INTO collisions FROM (
    SELECT section_id || ' roll ' || roll_no || ' x' || count(*) AS detail
    FROM enrolment
    WHERE status = 'active' AND roll_no IS NOT NULL
    GROUP BY section_id, roll_no
    HAVING count(*) > 1
  ) dupes;
  IF collisions IS NOT NULL THEN
    RAISE EXCEPTION 'Duplicate roll numbers block the uniqueness index: %', collisions
      USING HINT = 'Resolve the duplicates in enrolment.roll_no, then re-run this migration';
  END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS enrolment_roll_unique_per_section
    ON enrolment (section_id, roll_no)
    WHERE status = 'active' AND roll_no IS NOT NULL;

-- ----------------------------------------------------------------------------
-- Section capacity (GAP-10). The count is enforced in the enrolment and
-- admission-conversion paths; the column here records the deliberate override
-- so "why is 5A holding 33 children" has an answer a year later.
-- ----------------------------------------------------------------------------
ALTER TABLE enrolment
    ADD COLUMN IF NOT EXISTS over_capacity_reason TEXT;

-- ----------------------------------------------------------------------------
-- Bell schedule (GAP-12). A period master per grade band, so the school day is
-- re-timed in one place instead of across every slot, and so a slot can say
-- "period 3" rather than carrying its own opinion about when period 3 is.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bell_schedule (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    campus_id       UUID REFERENCES campus(id) ON DELETE CASCADE,
    code            TEXT NOT NULL,                    -- 'PRIMARY' | 'SENIOR'
    name            TEXT NOT NULL,
    effective_from  DATE NOT NULL,
    effective_to    DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    UNIQUE (school_id, code)
);

CREATE TABLE IF NOT EXISTS bell_period (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bell_schedule_id UUID NOT NULL REFERENCES bell_schedule(id) ON DELETE CASCADE,
    period_no       INT NOT NULL,
    label           TEXT NOT NULL,                    -- 'Period 1' | 'Short break'
    starts_at       TIME NOT NULL,
    ends_at         TIME NOT NULL,
    is_break        BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (ends_at > starts_at),
    UNIQUE (bell_schedule_id, period_no)
);

-- Which grades follow which bell schedule. A grade follows exactly one.
CREATE TABLE IF NOT EXISTS grade_bell_schedule (
    grade_id        UUID PRIMARY KEY REFERENCES grade(id) ON DELETE CASCADE,
    bell_schedule_id UUID NOT NULL REFERENCES bell_schedule(id) ON DELETE CASCADE
);

ALTER TABLE timetable_slot
    ADD COLUMN IF NOT EXISTS period_id UUID REFERENCES bell_period(id);
CREATE INDEX IF NOT EXISTS timetable_slot_period_idx ON timetable_slot (period_id);
-- Room clash detection reads this.
CREATE INDEX IF NOT EXISTS timetable_slot_room_idx ON timetable_slot (room, day_of_week);

-- A slot built against the bell schedule takes its times from the period, and
-- its period_no with them. The legacy columns stay (reports and the parent app
-- read them) but stop being an independent source of truth.
CREATE OR REPLACE FUNCTION slot_times_follow_period() RETURNS TRIGGER AS $$
DECLARE
  p RECORD;
BEGIN
  IF NEW.period_id IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT period_no, starts_at, ends_at, is_break INTO p FROM bell_period WHERE id = NEW.period_id;
  IF p.is_break THEN
    RAISE EXCEPTION 'Period % is a break; nothing can be timetabled into it', NEW.period_id
      USING ERRCODE = 'check_violation';
  END IF;
  NEW.period_no := p.period_no;
  NEW.starts_at := p.starts_at;
  NEW.ends_at   := p.ends_at;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS timetable_slot_period_times_trg ON timetable_slot;
CREATE TRIGGER timetable_slot_period_times_trg
    BEFORE INSERT OR UPDATE OF period_id ON timetable_slot
    FOR EACH ROW EXECUTE FUNCTION slot_times_follow_period();

-- Teacher weekly load ceiling, warned about at publish time (TT-04).
ALTER TABLE staff
    ADD COLUMN IF NOT EXISTS max_weekly_periods INT;
