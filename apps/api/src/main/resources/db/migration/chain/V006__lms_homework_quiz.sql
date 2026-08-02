-- ============================================================================
-- LMS — content, lesson plan, homework, quiz. Per §7 Layer 3.
-- ============================================================================

CREATE TABLE IF NOT EXISTS content_item (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    subject_id      UUID REFERENCES subject(id),
    -- Polymorphic curriculum binding — content tags a curriculum_node, not a
    -- syllabus chapter, so it can be reused across boards.
    curriculum_node_id UUID REFERENCES curriculum_node(id) ON DELETE SET NULL,
    title           TEXT NOT NULL,
    body            JSONB,                              -- rich blocks (text/image/video/math)
    file_id         UUID,                               -- attachment in object store
    visibility      TEXT NOT NULL DEFAULT 'school'
                      CHECK (visibility IN ('school','chain','private')),
    created_by_staff_id UUID REFERENCES staff(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS lesson_plan (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    section_id      UUID NOT NULL REFERENCES section(id) ON DELETE CASCADE,
    subject_id      UUID NOT NULL REFERENCES subject(id),
    curriculum_node_id UUID REFERENCES curriculum_node(id),
    title           TEXT NOT NULL,
    objectives      JSONB,                              -- LO ids + custom
    materials       JSONB,
    activities      JSONB,
    assessment_notes TEXT,
    planned_for     DATE,
    duration_minutes INT,
    status          TEXT NOT NULL DEFAULT 'draft'
                      CHECK (status IN ('draft','approved','delivered','archived')),
    created_by_staff_id UUID REFERENCES staff(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS assignment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    section_id      UUID NOT NULL REFERENCES section(id) ON DELETE CASCADE,
    subject_id      UUID NOT NULL REFERENCES subject(id),
    title           TEXT NOT NULL,
    instructions    TEXT,
    submission_type TEXT NOT NULL DEFAULT 'file'
                      CHECK (submission_type IN ('file','text','quiz','offline','lti')),
    due_at          TIMESTAMPTZ,
    max_marks       NUMERIC(8,2),
    rubric          JSONB,
    -- LTI linkage (per §12). When submission_type='lti', external tool fills these.
    lti_resource_link_id TEXT,
    lti_tool_code   TEXT,
    status          TEXT NOT NULL DEFAULT 'open'
                      CHECK (status IN ('draft','open','closed','graded')),
    created_by_staff_id UUID REFERENCES staff(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS assignment_submission (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id   UUID NOT NULL REFERENCES assignment(id) ON DELETE CASCADE,
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    body            TEXT,
    file_id         UUID,
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    marks           NUMERIC(8,2),
    feedback        TEXT,
    graded_by_staff_id UUID REFERENCES staff(id),
    graded_at       TIMESTAMPTZ,
    -- LTI Assignment & Grade Services score sink
    lti_score_payload JSONB,
    UNIQUE (assignment_id, student_id)
);

-- Quiz engine
CREATE TABLE IF NOT EXISTS quiz (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    subject_id      UUID REFERENCES subject(id),
    title           TEXT NOT NULL,
    duration_minutes INT,
    randomise       BOOLEAN NOT NULL DEFAULT FALSE,
    lockdown        BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_staff_id UUID REFERENCES staff(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS quiz_question (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quiz_id         UUID NOT NULL REFERENCES quiz(id) ON DELETE CASCADE,
    -- Question bank tagged to curriculum_node for reuse.
    curriculum_node_id UUID REFERENCES curriculum_node(id) ON DELETE SET NULL,
    kind            TEXT NOT NULL CHECK (kind IN ('mcq','multi','short','long','match','fill','upload')),
    prompt          TEXT NOT NULL,
    options         JSONB,
    answer          JSONB,                              -- correct answer / rubric
    marks           NUMERIC(6,2) NOT NULL DEFAULT 1,
    sort_order      INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS quiz_attempt (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quiz_id         UUID NOT NULL REFERENCES quiz(id) ON DELETE CASCADE,
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at    TIMESTAMPTZ,
    score           NUMERIC(8,2),
    responses       JSONB
);

-- LTI tool registry per §12. Lives chain-level since deployments are chain-wide.
CREATE TABLE IF NOT EXISTS lti_tool (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID REFERENCES school(id),         -- NULL = chain-wide
    code            TEXT NOT NULL UNIQUE,                -- 'khan' | 'mathspace' | 'readtheory'
    name            TEXT NOT NULL,
    issuer          TEXT NOT NULL,
    client_id       TEXT NOT NULL,
    auth_login_url  TEXT NOT NULL,
    auth_token_url  TEXT NOT NULL,
    keyset_url      TEXT NOT NULL,
    deployment_id   TEXT NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE
);
