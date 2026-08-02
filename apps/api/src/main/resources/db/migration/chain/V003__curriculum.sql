-- ============================================================================
-- Curriculum engine (per §9). Polymorphic — strategy is a code, not an enum.
-- Tree shape is universal (Strand→Unit→Topic→LO); strategy_data JSONB carries
-- board-specific shape.
-- ============================================================================

CREATE TABLE IF NOT EXISTS curriculum (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    board_code      TEXT NOT NULL,
    strategy_code   TEXT NOT NULL,                     -- dispatch key for strategy plugin
    name            TEXT NOT NULL,                     -- 'CBSE Class 10 — NCERT 2024'
    version         TEXT NOT NULL,
    grade_id        UUID REFERENCES grade(id),         -- nullable when curriculum spans grades
    subject_id      UUID REFERENCES subject(id),       -- nullable for cross-subject curricula
    source_template_id UUID,                           -- platform.curriculum_template.id (cloned from)
    strategy_data   JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_published    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (school_id, name, version)
);

-- Tree nodes. Materialised path keeps the tree queryable without recursive CTEs.
CREATE TABLE IF NOT EXISTS curriculum_node (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    curriculum_id   UUID NOT NULL REFERENCES curriculum(id) ON DELETE CASCADE,
    parent_id       UUID REFERENCES curriculum_node(id) ON DELETE CASCADE,
    node_type       TEXT NOT NULL CHECK (node_type IN ('strand','unit','chapter','topic','subtopic')),
    code            TEXT,                              -- '1.4' | 'N1' | 'Ch3'
    name            TEXT NOT NULL,
    sort_order      INT NOT NULL DEFAULT 0,
    path            TEXT NOT NULL,                     -- materialised: '/strand-uuid/unit-uuid/...'
    depth           INT NOT NULL DEFAULT 0,
    strategy_data   JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS curr_node_curriculum_idx ON curriculum_node(curriculum_id);
CREATE INDEX IF NOT EXISTS curr_node_parent_idx     ON curriculum_node(parent_id);
CREATE INDEX IF NOT EXISTS curr_node_path_idx       ON curriculum_node(path text_pattern_ops);

CREATE TABLE IF NOT EXISTS learning_outcome (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    curriculum_node_id UUID NOT NULL REFERENCES curriculum_node(id) ON DELETE CASCADE,
    code            TEXT,                              -- 'LO 1.4.2'
    statement       TEXT NOT NULL,
    bloom_level     TEXT CHECK (bloom_level IN ('remember','understand','apply','analyse','evaluate','create')),
    sort_order      INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS lo_node_idx ON learning_outcome(curriculum_node_id);

-- Resolve forward FK from section.curriculum_id
ALTER TABLE section
    ADD CONSTRAINT section_curriculum_fk
    FOREIGN KEY (curriculum_id) REFERENCES curriculum(id) ON DELETE SET NULL;
