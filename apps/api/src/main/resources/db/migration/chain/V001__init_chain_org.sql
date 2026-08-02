-- ============================================================================
-- Chain schema: organisational core.
-- Run per-chain. Search path is set to the chain schema by the migrator.
-- Per design §5 — School > Campus > AY > Term > Grade > Section.
-- ============================================================================

CREATE TABLE IF NOT EXISTS school (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            TEXT NOT NULL UNIQUE,
    name            TEXT NOT NULL,
    board_code      TEXT NOT NULL,                  -- default board; sections may override
    gstin           TEXT,
    state_code      TEXT,
    address         JSONB,
    timezone        TEXT NOT NULL DEFAULT 'Asia/Kolkata',
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS campus (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    address         JSONB,
    is_primary      BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (school_id, name)
);

CREATE TABLE IF NOT EXISTS academic_year (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    code            TEXT NOT NULL,                  -- '2026-27'
    starts_on       DATE NOT NULL,
    ends_on         DATE NOT NULL,
    is_current      BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (school_id, code),
    CHECK (ends_on > starts_on)
);
-- Only one current AY per school.
CREATE UNIQUE INDEX IF NOT EXISTS ay_one_current_per_school
    ON academic_year (school_id) WHERE is_current;

CREATE TABLE IF NOT EXISTS term (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    academic_year_id UUID NOT NULL REFERENCES academic_year(id) ON DELETE CASCADE,
    code            TEXT NOT NULL,                  -- 'T1' | 'PT1' | 'HY' | 'Annual'
    name            TEXT NOT NULL,
    starts_on       DATE NOT NULL,
    ends_on         DATE NOT NULL,
    UNIQUE (academic_year_id, code)
);

CREATE TABLE IF NOT EXISTS grade (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    code            TEXT NOT NULL,                  -- '1' .. '12' | 'IGCSE-Y10' | 'A-Y12'
    name            TEXT NOT NULL,
    sort_order      INT NOT NULL DEFAULT 0,
    UNIQUE (school_id, code)
);

CREATE TABLE IF NOT EXISTS section (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    grade_id        UUID NOT NULL REFERENCES grade(id) ON DELETE CASCADE,
    academic_year_id UUID NOT NULL REFERENCES academic_year(id) ON DELETE CASCADE,
    code            TEXT NOT NULL,                  -- 'A' | 'B' | 'CIE'
    name            TEXT NOT NULL,
    -- Polymorphic curriculum binding. The authoritative curriculum lives here
    -- (per §5 "Section carries the authoritative curriculum_id"). Strategy
    -- discriminator stored alongside so strategy code can be dispatched without
    -- a join (hot path).
    curriculum_id   UUID,                            -- FK declared post curriculum table
    strategy_code   TEXT NOT NULL,                   -- 'CBSE-CCE-2024' | 'CIE-IGCSE'
    capacity        INT,
    UNIQUE (school_id, grade_id, academic_year_id, code)
);

CREATE TABLE IF NOT EXISTS subject (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    code            TEXT NOT NULL,                  -- 'MATH' | '0580' (Cambridge syllabus code)
    name            TEXT NOT NULL,
    board_code      TEXT,
    UNIQUE (school_id, code)
);

CREATE TABLE IF NOT EXISTS section_subject_teacher (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id      UUID NOT NULL REFERENCES section(id) ON DELETE CASCADE,
    subject_id      UUID NOT NULL REFERENCES subject(id) ON DELETE CASCADE,
    teacher_staff_id UUID NOT NULL,                 -- → staff(id), declared after staff table
    is_primary      BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (section_id, subject_id, teacher_staff_id)
);

-- Convenience indexes for tenant queries
CREATE INDEX IF NOT EXISTS campus_school_idx ON campus(school_id);
CREATE INDEX IF NOT EXISTS ay_school_idx     ON academic_year(school_id);
CREATE INDEX IF NOT EXISTS grade_school_idx  ON grade(school_id);
CREATE INDEX IF NOT EXISTS section_school_idx ON section(school_id);
