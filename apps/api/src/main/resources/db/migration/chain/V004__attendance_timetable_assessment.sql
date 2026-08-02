-- ============================================================================
-- Attendance, Timetable, Assessment + Marks. Per §7 Layer 1.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Timetable
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS timetable_slot (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id      UUID NOT NULL REFERENCES section(id) ON DELETE CASCADE,
    subject_id      UUID NOT NULL REFERENCES subject(id),
    teacher_staff_id UUID NOT NULL REFERENCES staff(id),
    day_of_week     INT NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
    period_no       INT NOT NULL,
    starts_at       TIME NOT NULL,
    ends_at         TIME NOT NULL,
    room            TEXT,
    effective_from  DATE NOT NULL,
    effective_to    DATE,
    CHECK (ends_at > starts_at)
);
CREATE INDEX IF NOT EXISTS tt_section_dow_idx ON timetable_slot(section_id, day_of_week);
-- Teacher clash detection helper.
CREATE INDEX IF NOT EXISTS tt_teacher_dow_idx ON timetable_slot(teacher_staff_id, day_of_week);

-- ----------------------------------------------------------------------------
-- Attendance. Sources: manual / biometric / rfid.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS attendance_record (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    section_id      UUID NOT NULL REFERENCES section(id),
    on_date         DATE NOT NULL,
    period_no       INT,                                -- NULL → day-level
    status          TEXT NOT NULL
                      CHECK (status IN ('present','absent','late','leave','excused','half_day')),
    source          TEXT NOT NULL DEFAULT 'manual'
                      CHECK (source IN ('manual','biometric','rfid','self','auto')),
    marked_by_staff_id UUID REFERENCES staff(id),
    marked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    notes           TEXT,
    UNIQUE (student_id, on_date, period_no)
);
CREATE INDEX IF NOT EXISTS att_date_section_idx ON attendance_record(on_date, section_id);
CREATE INDEX IF NOT EXISTS att_school_date_idx  ON attendance_record(school_id, on_date);

-- Staff attendance (biometric primarily).
CREATE TABLE IF NOT EXISTS staff_attendance (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    staff_id        UUID NOT NULL REFERENCES staff(id) ON DELETE CASCADE,
    on_date         DATE NOT NULL,
    in_at           TIMESTAMPTZ,
    out_at          TIMESTAMPTZ,
    status          TEXT NOT NULL DEFAULT 'present'
                      CHECK (status IN ('present','absent','leave','half_day','wfh')),
    source          TEXT NOT NULL DEFAULT 'manual',
    UNIQUE (staff_id, on_date)
);

CREATE TABLE IF NOT EXISTS leave_application (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    subject_type    TEXT NOT NULL CHECK (subject_type IN ('student','staff')),
    subject_id      UUID NOT NULL,
    from_date       DATE NOT NULL,
    to_date         DATE NOT NULL,
    reason          TEXT,
    status          TEXT NOT NULL DEFAULT 'pending'
                      CHECK (status IN ('pending','approved','rejected','cancelled')),
    approver_staff_id UUID REFERENCES staff(id),
    decided_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (to_date >= from_date)
);

-- ----------------------------------------------------------------------------
-- Assessment + Marks. Per §8 — Marks are on AssessmentComponent which is owned
-- by an Assessment owned by a curriculum strategy. Shape varies per strategy.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS assessment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    section_id      UUID NOT NULL REFERENCES section(id) ON DELETE CASCADE,
    subject_id      UUID NOT NULL REFERENCES subject(id),
    term_id         UUID REFERENCES term(id),
    strategy_code   TEXT NOT NULL,                     -- 'CBSE-CCE-2024' | 'CIE-IGCSE'
    name            TEXT NOT NULL,                     -- 'Periodic Test 1' | 'Paper 2'
    assessment_type TEXT NOT NULL,                     -- 'PT' | 'HY' | 'Annual' | 'CoScholastic' | 'Component' | 'Coursework'
    max_marks       NUMERIC(8,2),
    weight_pct      NUMERIC(5,2),                       -- contribution to final grade
    scheduled_on    DATE,
    status          TEXT NOT NULL DEFAULT 'draft'
                      CHECK (status IN ('draft','scheduled','in_progress','marking','locked','published')),
    strategy_data   JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS assessment_component (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id   UUID NOT NULL REFERENCES assessment(id) ON DELETE CASCADE,
    code            TEXT NOT NULL,                     -- 'Q1' | 'PaperA' | 'Practical'
    name            TEXT NOT NULL,
    max_marks       NUMERIC(8,2) NOT NULL,
    weight_pct      NUMERIC(5,2),
    rubric          JSONB,
    sort_order      INT NOT NULL DEFAULT 0,
    UNIQUE (assessment_id, code)
);

CREATE TABLE IF NOT EXISTS mark (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    assessment_component_id UUID NOT NULL REFERENCES assessment_component(id) ON DELETE CASCADE,
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    raw_marks       NUMERIC(8,2),
    grade_letter    TEXT,                              -- 'A1' | 'A*' | '7' (from grading_scale)
    remarks         TEXT,
    is_absent       BOOLEAN NOT NULL DEFAULT FALSE,
    moderated_by_staff_id UUID REFERENCES staff(id),
    moderated_at    TIMESTAMPTZ,
    entered_by_staff_id UUID REFERENCES staff(id),
    entered_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (assessment_component_id, student_id)
);
CREATE INDEX IF NOT EXISTS mark_student_idx ON mark(student_id);

-- Report cards — generated artefact, references assessment data + a template.
CREATE TABLE IF NOT EXISTS report_card (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    academic_year_id UUID NOT NULL REFERENCES academic_year(id),
    term_id         UUID REFERENCES term(id),
    strategy_code   TEXT NOT NULL,
    template_code   TEXT NOT NULL,
    payload         JSONB NOT NULL,                    -- rendered fields
    file_id         UUID,                              -- → file module's id
    is_locked       BOOLEAN NOT NULL DEFAULT FALSE,
    parent_visible_from TIMESTAMPTZ,
    generated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS report_card_student_idx ON report_card(student_id);
