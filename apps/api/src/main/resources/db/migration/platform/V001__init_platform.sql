-- ============================================================================
-- Platform schema: cross-tenant shared data.
-- Per design §5a — chains, plans, master curricula, BSP templates, region cfg,
-- platform admin users, board enums.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ---------------------------------------------------------------------------
-- Chains (tenancy root). One row per chain. Schema name derived from slug.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform.chain (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            TEXT NOT NULL UNIQUE,
    name            TEXT NOT NULL,
    schema_name     TEXT NOT NULL UNIQUE,
    plan_code       TEXT NOT NULL DEFAULT 'starter',
    region          TEXT NOT NULL DEFAULT 'ap-south-1',
    status          TEXT NOT NULL DEFAULT 'active'
                      CHECK (status IN ('active','suspended','offboarding','deleted')),
    schema_version  INT  NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Subscription plan catalogue.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform.plan (
    code            TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    features        JSONB NOT NULL DEFAULT '{}'::jsonb,
    price_inr_per_student_month NUMERIC(10,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO platform.plan (code, name, features, price_inr_per_student_month) VALUES
  ('starter',    'Starter',    '{"sis":true,"fees":true,"whatsapp":false,"hardware":false}'::jsonb,  60),
  ('growth',     'Growth',     '{"sis":true,"fees":true,"whatsapp":true,"hardware":true,"lms":true}'::jsonb, 120),
  ('enterprise', 'Enterprise', '{"sis":true,"fees":true,"whatsapp":true,"hardware":true,"lms":true,"hq":true,"sso":true}'::jsonb, 250)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Platform users — staff of the SaaS vendor (us), not school staff.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform.platform_user (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           TEXT NOT NULL UNIQUE,
    name            TEXT NOT NULL,
    role            TEXT NOT NULL CHECK (role IN ('platform_admin','support','finance')),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Master curriculum templates. Per §9 — versioned tree, board-strategy bound.
-- Chains import/clone from these into their own chain_X.curriculum.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform.curriculum_template (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_code      TEXT NOT NULL,        -- 'CBSE' | 'CIE' | 'IB' | 'ICSE' ...
    strategy_code   TEXT NOT NULL,        -- 'CBSE-CCE-2024' | 'CIE-IGCSE' ...
    name            TEXT NOT NULL,
    version         TEXT NOT NULL,
    grade_band      TEXT,                  -- '1-5' | '9-10' | 'IGCSE'
    payload         JSONB NOT NULL,        -- full tree: strand→unit→topic→LO
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (board_code, strategy_code, name, version)
);

-- ---------------------------------------------------------------------------
-- Board enumerations + grading scales.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform.board (
    code        TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    country     TEXT NOT NULL,
    strategy_code TEXT NOT NULL
);

INSERT INTO platform.board (code, name, country, strategy_code) VALUES
  ('CBSE', 'Central Board of Secondary Education',                'IN', 'CBSE-CCE-2024'),
  ('CIE',  'Cambridge Assessment International Education',        'GB', 'CIE-IGCSE'),
  ('ICSE', 'Indian Certificate of Secondary Education (CISCE)',   'IN', 'ICSE-2024'),
  ('IB',   'International Baccalaureate',                          'CH', 'IB-MYP-DP')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE IF NOT EXISTS platform.grading_scale (
    code        TEXT PRIMARY KEY,
    board_code  TEXT NOT NULL REFERENCES platform.board(code),
    scale       JSONB NOT NULL
);

INSERT INTO platform.grading_scale (code, board_code, scale) VALUES
  ('CBSE_A1_E', 'CBSE',
    '[
      {"grade":"A1","min":91,"max":100},
      {"grade":"A2","min":81,"max":90},
      {"grade":"B1","min":71,"max":80},
      {"grade":"B2","min":61,"max":70},
      {"grade":"C1","min":51,"max":60},
      {"grade":"C2","min":41,"max":50},
      {"grade":"D", "min":33,"max":40},
      {"grade":"E", "min":0, "max":32}
    ]'::jsonb),
  ('CIE_ASTAR_E', 'CIE',
    '["A*","A","B","C","D","E","U"]'::jsonb),
  ('CIE_9_1', 'CIE',
    '["9","8","7","6","5","4","3","2","1","U"]'::jsonb)
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- WhatsApp BSP template registry. Per §10 — approval lifecycle is platform-wide.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform.whatsapp_template (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            TEXT NOT NULL,        -- 'absence_alert' | 'payment_success' ...
    language        TEXT NOT NULL,        -- 'en' | 'hi' | 'ta' ...
    category        TEXT NOT NULL CHECK (category IN ('utility','marketing','authentication')),
    body            TEXT NOT NULL,
    variables       JSONB NOT NULL DEFAULT '[]'::jsonb,
    status          TEXT NOT NULL DEFAULT 'draft'
                      CHECK (status IN ('draft','submitted','approved','rejected','paused')),
    bsp_id          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (code, language)
);

-- ---------------------------------------------------------------------------
-- Auth: per-chain JWT key material is stored here so platform-admin tokens
-- can be verified alongside chain tokens. Per-chain row carries the kid.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform.signing_key (
    kid             TEXT PRIMARY KEY,
    chain_id        UUID,
    algorithm       TEXT NOT NULL DEFAULT 'HS256',
    secret          TEXT NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Region config (data residency hints).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS platform.region_config (
    region          TEXT PRIMARY KEY,
    object_store_bucket TEXT NOT NULL,
    primary_kms_arn TEXT
);

INSERT INTO platform.region_config (region, object_store_bucket, primary_kms_arn) VALUES
  ('ap-south-1', 'schoolsoft-prod-ap-south-1', NULL)
ON CONFLICT (region) DO NOTHING;
