-- ============================================================================
-- Phase 5 — assessment and report card content.
--
-- The gradebook could store a number against a child and a blob of JSON called
-- a report card. It could not say why a number was missing, could not stop a
-- moderated mark from erasing the one the teacher entered, could not tell a
-- student sitting two papers at once, and could not answer the one question
-- the next academic year begins with: is this child promoted?
--
-- Everything here exists to make those four answerable, and the last of them
-- is what Phase 6 reads.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Grade boundaries. Each school gets a default scale from its board, and may
-- add its own; the curriculum strategy reads the scale rather than hard-coding
-- one, which is what lets a CBSE and a Cambridge school in the same chain
-- grade differently without a config fork (GAP-29).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS grade_scale (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    code            TEXT NOT NULL,
    name            TEXT NOT NULL,
    strategy_code   TEXT NOT NULL,
    -- The percentage at or above which a subject counts as passed. CBSE's 33
    -- and Cambridge's 40 are the same rule with different numbers.
    pass_pct        NUMERIC(5,2) NOT NULL DEFAULT 33,
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (school_id, code)
);

-- One default per school: the report card must not have to choose.
CREATE UNIQUE INDEX IF NOT EXISTS grade_scale_one_default_per_school
    ON grade_scale (school_id) WHERE is_default;

CREATE TABLE IF NOT EXISTS grade_band (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grade_scale_id  UUID NOT NULL REFERENCES grade_scale(id) ON DELETE CASCADE,
    grade           TEXT NOT NULL,                     -- 'A1' | 'A*' | '7'
    min_pct         NUMERIC(5,2) NOT NULL,
    max_pct         NUMERIC(5,2) NOT NULL,
    grade_point     NUMERIC(4,2),
    descriptor      TEXT,
    UNIQUE (grade_scale_id, grade),
    CHECK (max_pct >= min_pct)
);
CREATE INDEX IF NOT EXISTS grade_band_scale_idx ON grade_band (grade_scale_id, min_pct DESC);

-- Seed each existing school its board's scale. Written as INSERT..SELECT over
-- `school` so a chain with fifty schools needs no per-school migration; new
-- schools get theirs from GradeScaleRepository at first use.
INSERT INTO grade_scale (id, school_id, code, name, strategy_code, pass_pct, is_default)
SELECT gen_random_uuid(), s.id,
       CASE WHEN s.board_code = 'CIE' THEN 'CIE_ASTAR_E' ELSE 'CBSE_A1_E' END,
       CASE WHEN s.board_code = 'CIE' THEN 'Cambridge A*–U' ELSE 'CBSE A1–E' END,
       CASE WHEN s.board_code = 'CIE' THEN 'CIE-IGCSE' ELSE 'CBSE-CCE-2024' END,
       CASE WHEN s.board_code = 'CIE' THEN 40 ELSE 33 END,
       TRUE
FROM school s
WHERE NOT EXISTS (SELECT 1 FROM grade_scale gs WHERE gs.school_id = s.id)
ON CONFLICT DO NOTHING;

INSERT INTO grade_band (grade_scale_id, grade, min_pct, max_pct, grade_point)
SELECT gs.id, b.grade, b.min_pct, b.max_pct, b.grade_point
FROM grade_scale gs
JOIN LATERAL (
    VALUES ('A1', 91, 100, 10), ('A2', 81, 90.99, 9), ('B1', 71, 80.99, 8), ('B2', 61, 70.99, 7),
           ('C1', 51, 60.99, 6), ('C2', 41, 50.99, 5), ('D', 33, 40.99, 4), ('E', 0, 32.99, 0)
) AS b(grade, min_pct, max_pct, grade_point) ON gs.code = 'CBSE_A1_E'
ON CONFLICT DO NOTHING;

INSERT INTO grade_band (grade_scale_id, grade, min_pct, max_pct, grade_point)
SELECT gs.id, b.grade, b.min_pct, b.max_pct, b.grade_point
FROM grade_scale gs
JOIN LATERAL (
    VALUES ('A*', 90, 100, 8), ('A', 80, 89.99, 7), ('B', 70, 79.99, 6), ('C', 60, 69.99, 5),
           ('D', 50, 59.99, 4), ('E', 40, 49.99, 3), ('U', 0, 39.99, 0)
) AS b(grade, min_pct, max_pct, grade_point) ON gs.code = 'CIE_ASTAR_E'
ON CONFLICT DO NOTHING;

-- ----------------------------------------------------------------------------
-- Assessment policy. Two school-level decisions the code must not make for
-- them: whether an unpaid bill withholds a report card (ASMT-15), and how
-- exactly component weights have to add up (ASMT-03).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS assessment_policy (
    school_id           UUID PRIMARY KEY REFERENCES school(id) ON DELETE CASCADE,
    -- 'withhold' keeps a report card from a family in arrears; 'release' is
    -- the humane default, and several boards require it.
    dues_block_policy   TEXT NOT NULL DEFAULT 'release'
                          CHECK (dues_block_policy IN ('withhold','release')),
    dues_block_threshold NUMERIC(12,2) NOT NULL DEFAULT 0,
    -- Rounding slack when checking that component weights sum to 100.
    weight_tolerance_pct NUMERIC(5,2) NOT NULL DEFAULT 0.01,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- Marks: why a number is missing (ASMT-04, ASMT-05).
--
-- `is_absent` could say a child was not there. It could not distinguish a
-- blank from a zero, an exam missed on medical grounds from one skipped, or a
-- paper the student was never entered for. All four have to render differently
-- on a report card and count differently in an average, so the boolean becomes
-- a status and the boolean goes away — two columns meaning nearly the same
-- thing is how they drift apart.
-- ----------------------------------------------------------------------------
ALTER TABLE mark
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'entered';

UPDATE mark SET status = 'absent' WHERE is_absent;
UPDATE mark SET status = 'pending' WHERE NOT is_absent AND raw_marks IS NULL;

ALTER TABLE mark DROP COLUMN IF EXISTS is_absent;

ALTER TABLE mark
    DROP CONSTRAINT IF EXISTS mark_status_check;
ALTER TABLE mark
    ADD CONSTRAINT mark_status_check
    CHECK (status IN ('entered','pending','absent','medical_leave','exempt'));

-- A marked score has a number; every other status must not carry one. This is
-- the constraint that makes "blank" and "zero" different rows rather than
-- different readings of the same row.
ALTER TABLE mark
    DROP CONSTRAINT IF EXISTS mark_score_matches_status;
ALTER TABLE mark
    ADD CONSTRAINT mark_score_matches_status
    CHECK ((status = 'entered' AND raw_marks IS NOT NULL)
        OR (status <> 'entered' AND raw_marks IS NULL));

-- ----------------------------------------------------------------------------
-- Mark revisions (ASMT-07, ASMT-08).
--
-- Re-evaluation and moderation supersede a mark; they never discard one. The
-- mark row is the current truth, the revision chain is how it got there, and
-- the pair together is what a school shows a parent who asks what changed.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mark_revision (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id           UUID NOT NULL REFERENCES school(id),
    mark_id             UUID NOT NULL REFERENCES mark(id) ON DELETE CASCADE,
    revision_no         INT NOT NULL,
    kind                TEXT NOT NULL
                          CHECK (kind IN ('correction','re_evaluation','moderation','unlock_edit')),
    old_raw_marks       NUMERIC(8,2),
    old_status          TEXT NOT NULL,
    old_grade_letter    TEXT,
    new_raw_marks       NUMERIC(8,2),
    new_status          TEXT NOT NULL,
    new_grade_letter    TEXT,
    reason              TEXT NOT NULL CHECK (length(btrim(reason)) > 0),
    changed_by_user_id  UUID REFERENCES user_account(id),
    changed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (mark_id, revision_no)
);
CREATE INDEX IF NOT EXISTS mark_revision_mark_idx ON mark_revision (mark_id, revision_no DESC);

-- A parent asks; the school decides. The request is kept whatever the outcome,
-- because "we looked at it and the mark stands" is an answer a family is owed.
CREATE TABLE IF NOT EXISTS mark_reevaluation (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id           UUID NOT NULL REFERENCES school(id),
    mark_id             UUID NOT NULL REFERENCES mark(id) ON DELETE CASCADE,
    student_id          UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    reason              TEXT NOT NULL CHECK (length(btrim(reason)) > 0),
    requested_by_user_id UUID REFERENCES user_account(id),
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    status              TEXT NOT NULL DEFAULT 'pending'
                          CHECK (status IN ('pending','upheld','revised','rejected')),
    decided_by_user_id  UUID REFERENCES user_account(id),
    decided_at          TIMESTAMPTZ,
    decision_note       TEXT,
    revision_id         UUID REFERENCES mark_revision(id) ON DELETE SET NULL
);

-- One open request per mark: a second one is the same conversation twice.
CREATE UNIQUE INDEX IF NOT EXISTS mark_reevaluation_one_pending
    ON mark_reevaluation (mark_id) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS mark_reevaluation_school_status_idx
    ON mark_reevaluation (school_id, status, requested_at DESC);

-- ----------------------------------------------------------------------------
-- Exam operations (ASMT-09, TT-09).
--
-- A schedule is the exam week; a session is one paper sat by one grade at one
-- time. Sessions hang off the grade rather than the section because a paper is
-- set for a cohort, and clash detection runs over what each *student* sits —
-- with option blocks, two sections' timetables can be clash-free while one
-- child is booked into two rooms at once.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS exam_schedule (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    academic_year_id UUID NOT NULL REFERENCES academic_year(id),
    term_id         UUID REFERENCES term(id),
    code            TEXT NOT NULL,
    name            TEXT NOT NULL,
    starts_on       DATE NOT NULL,
    ends_on         DATE NOT NULL,
    status          TEXT NOT NULL DEFAULT 'draft'
                      CHECK (status IN ('draft','published','cancelled')),
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (school_id, academic_year_id, code),
    CHECK (ends_on >= starts_on)
);

CREATE TABLE IF NOT EXISTS exam_session (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exam_schedule_id UUID NOT NULL REFERENCES exam_schedule(id) ON DELETE CASCADE,
    school_id       UUID NOT NULL REFERENCES school(id),
    grade_id        UUID NOT NULL REFERENCES grade(id),
    subject_id      UUID NOT NULL REFERENCES subject(id),
    paper_code      TEXT NOT NULL DEFAULT 'P1',
    name            TEXT NOT NULL,
    on_date         DATE NOT NULL,
    starts_at       TIME NOT NULL,
    ends_at         TIME NOT NULL,
    room            TEXT,
    invigilator_staff_id UUID REFERENCES staff(id),
    max_marks       NUMERIC(8,2),
    -- The assessment the marks land on, when one has been created for it.
    assessment_id   UUID REFERENCES assessment(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (exam_schedule_id, grade_id, subject_id, paper_code),
    CHECK (ends_at > starts_at)
);
CREATE INDEX IF NOT EXISTS exam_session_date_idx ON exam_session (school_id, on_date);
CREATE INDEX IF NOT EXISTS exam_session_grade_date_idx ON exam_session (grade_id, on_date);

-- An invigilator is one person: two rooms at the same hour is the same mistake
-- as a student in two rooms, and cheaper to catch here than on the day.
CREATE UNIQUE INDEX IF NOT EXISTS exam_session_invigilator_slot
    ON exam_session (invigilator_staff_id, on_date, starts_at)
    WHERE invigilator_staff_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS exam_hall_ticket (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    exam_schedule_id UUID NOT NULL REFERENCES exam_schedule(id) ON DELETE CASCADE,
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    ticket_no       TEXT NOT NULL,
    seat_no         TEXT,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (exam_schedule_id, student_id),
    UNIQUE (school_id, ticket_no)
);

-- ----------------------------------------------------------------------------
-- Report card content model (GAP-13).
--
-- The payload stays for board-specific extras, but everything a school, a
-- parent or the rollover reads is now a column or a row: you cannot query
-- "which children have no promotion decision" out of free-form JSON, and that
-- query is the first step of closing a year.
-- ----------------------------------------------------------------------------
ALTER TABLE report_card
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'draft',
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS section_id UUID REFERENCES section(id),
    ADD COLUMN IF NOT EXISTS promotion_decision TEXT,
    ADD COLUMN IF NOT EXISTS promotion_decided_by_user_id UUID REFERENCES user_account(id),
    ADD COLUMN IF NOT EXISTS teacher_remarks TEXT,
    ADD COLUMN IF NOT EXISTS principal_remarks TEXT,
    ADD COLUMN IF NOT EXISTS grade_scale_code TEXT,
    ADD COLUMN IF NOT EXISTS total_marks NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS total_max_marks NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS overall_pct NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS overall_grade TEXT,
    ADD COLUMN IF NOT EXISTS class_rank INT,
    ADD COLUMN IF NOT EXISTS class_size INT,
    ADD COLUMN IF NOT EXISTS percentile NUMERIC(5,2),
    -- What the cohort was ordered on. Stored rather than recomputed because
    -- the boards order on different things — CBSE on aggregate percentage,
    -- Cambridge on mean grade point — and a rank has to be reproducible from
    -- the card itself months later (ASMT-11).
    ADD COLUMN IF NOT EXISTS rank_key NUMERIC(8,3),
    ADD COLUMN IF NOT EXISTS attendance_working_days INT,
    ADD COLUMN IF NOT EXISTS attendance_present_days NUMERIC(6,1),
    ADD COLUMN IF NOT EXISTS attendance_pct NUMERIC(5,2),
    -- A mid-year joiner's card says which terms it can speak for, and why the
    -- others are missing — a blank row reads as a failure (ASMT-14).
    ADD COLUMN IF NOT EXISTS enrolled_from DATE,
    ADD COLUMN IF NOT EXISTS terms_attended INT,
    ADD COLUMN IF NOT EXISTS terms_in_year INT,
    ADD COLUMN IF NOT EXISTS coverage_note TEXT,
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE report_card
    DROP CONSTRAINT IF EXISTS report_card_status_check;
ALTER TABLE report_card
    ADD CONSTRAINT report_card_status_check
    CHECK (status IN ('draft','locked','published'));

ALTER TABLE report_card
    DROP CONSTRAINT IF EXISTS report_card_promotion_check;
ALTER TABLE report_card
    ADD CONSTRAINT report_card_promotion_check
    CHECK (promotion_decision IS NULL
        OR promotion_decision IN ('promote','detain','graduate'));

-- Existing rows predate the status column; the fixture's locked cards and any
-- production card that was locked keep their meaning.
UPDATE report_card SET status = 'locked' WHERE is_locked AND status = 'draft';
UPDATE report_card SET status = 'published'
    WHERE parent_visible_from IS NOT NULL AND parent_visible_from <= now() AND status = 'locked';

-- One card per student per term per template. Regeneration updates that row —
-- before this, a locked card could be quietly superseded by a second insert
-- and the school had two answers to one question (ASMT-12).
DELETE FROM report_card rc USING report_card older
WHERE rc.student_id = older.student_id
  AND rc.academic_year_id = older.academic_year_id
  AND rc.term_id IS NOT DISTINCT FROM older.term_id
  AND rc.template_code = older.template_code
  AND rc.generated_at < older.generated_at;

CREATE UNIQUE INDEX IF NOT EXISTS report_card_one_per_student_term
    ON report_card (student_id, academic_year_id, term_id, template_code)
    NULLS NOT DISTINCT;

CREATE TABLE IF NOT EXISTS report_card_subject (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_card_id  UUID NOT NULL REFERENCES report_card(id) ON DELETE CASCADE,
    subject_id      UUID NOT NULL REFERENCES subject(id),
    subject_code    TEXT NOT NULL,
    subject_name    TEXT NOT NULL,
    origin          TEXT NOT NULL DEFAULT 'compulsory',
    marks_obtained  NUMERIC(8,2),
    max_marks       NUMERIC(8,2),
    percentage      NUMERIC(5,2),
    grade_letter    TEXT,
    -- 'marked' | 'absent' | 'medical_leave' | 'exempt' | 'not_assessed'.
    -- An absence renders AB and is excluded from the average; it is not a zero.
    result_status   TEXT NOT NULL DEFAULT 'marked',
    is_passing      BOOLEAN,
    remarks         TEXT,
    sort_order      INT NOT NULL DEFAULT 0,
    UNIQUE (report_card_id, subject_id)
);

CREATE TABLE IF NOT EXISTS report_card_coscholastic (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_card_id  UUID NOT NULL REFERENCES report_card(id) ON DELETE CASCADE,
    area_code       TEXT NOT NULL,                     -- 'WORK_EDU' | 'ART' | 'DISCIPLINE'
    area_name       TEXT NOT NULL,
    rating          TEXT NOT NULL,                     -- 'A' | 'B' | 'Outstanding'
    remarks         TEXT,
    sort_order      INT NOT NULL DEFAULT 0,
    UNIQUE (report_card_id, area_code)
);

CREATE INDEX IF NOT EXISTS report_card_year_status_idx
    ON report_card (academic_year_id, status);
CREATE INDEX IF NOT EXISTS report_card_promotion_idx
    ON report_card (academic_year_id, promotion_decision);

-- ----------------------------------------------------------------------------
-- RLS for the tables added above, by the same sweep Phase 3 used: anything
-- school-scoped without a policy gets one, so protection is the default rather
-- than something the next migration's author has to remember.
-- ----------------------------------------------------------------------------
DO $$
DECLARE t TEXT;
BEGIN
    FOR t IN
        SELECT c.table_name FROM information_schema.columns c
        WHERE c.table_schema = current_schema()
          AND c.column_name = 'school_id'
          AND c.table_name <> 'school'
          AND c.table_name IN (SELECT table_name FROM information_schema.tables
                               WHERE table_schema = current_schema() AND table_type = 'BASE TABLE')
          AND NOT EXISTS (
              SELECT 1 FROM pg_policies p
              WHERE p.schemaname = current_schema() AND p.tablename = c.table_name)
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I_school_isolation ON %I
               USING (is_trusted_session() OR school_id = current_school_id() OR current_school_id() IS NULL)
               WITH CHECK (is_trusted_session() OR school_id = current_school_id() OR current_school_id() IS NULL)',
            t, t);
    END LOOP;
END $$;
