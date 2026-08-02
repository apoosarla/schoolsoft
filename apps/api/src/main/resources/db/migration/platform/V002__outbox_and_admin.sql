-- ============================================================================
-- Platform-level outbox (used by jobs/eventbus) + schema version tracking.
-- ============================================================================

-- Spring Modulith uses its own event_publication table; we let it auto-create.
-- This is the *cross-chain* outbox for platform-level events (chain.created etc.).
CREATE TABLE IF NOT EXISTS platform.outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  TEXT NOT NULL,
    aggregate_id    TEXT NOT NULL,
    event_type      TEXT NOT NULL,
    payload         JSONB NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS outbox_unpublished_idx
  ON platform.outbox (occurred_at) WHERE published_at IS NULL;

-- Tracks which chain schema is at which migration version (Risk R11).
CREATE TABLE IF NOT EXISTS platform.chain_schema_version (
    chain_id        UUID PRIMARY KEY REFERENCES platform.chain(id) ON DELETE CASCADE,
    schema_version  INT NOT NULL,
    last_migrated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT
);
