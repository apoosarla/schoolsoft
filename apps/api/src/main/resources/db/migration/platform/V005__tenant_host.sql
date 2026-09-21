-- ============================================================================
-- The hostname registry: the one lookup a browser is allowed to make before it
-- holds a token.
--
-- Sign-in cannot ask "which school are you?" with a picker — a list of tenants
-- is a customer list, and a searchable one hands every competitor the roster.
-- So the school is *resolved*, never *chosen*: a host maps to exactly one
-- tenant, and someone who does not already know a host learns nothing here.
--
-- It lives in `platform` and not in `school_theme` (which is per-chain, V008)
-- because the lookup happens before any chain schema is known — that is the
-- whole chicken-and-egg this table exists to break. It holds no display data:
-- the resolver enters the chain it names and reads the school's own row, so
-- there is one source of truth for a school's name and colours.
-- ============================================================================

CREATE TABLE IF NOT EXISTS platform.tenant_host (
    host        TEXT PRIMARY KEY,
    chain_id    UUID NOT NULL REFERENCES platform.chain(id) ON DELETE CASCADE,
    school_id   UUID,
    kind        TEXT NOT NULL DEFAULT 'school'
                  CHECK (kind IN ('school','chain_hq')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A school door names its school; a chain's HQ door deliberately does not.
    CONSTRAINT tenant_host_school_matches_kind
        CHECK ((kind = 'school') = (school_id IS NOT NULL)),

    -- Hosts compare case-insensitively in DNS, so store them folded and let
    -- the primary key do the de-duplicating.
    CONSTRAINT tenant_host_is_folded CHECK (host = lower(host))
);

CREATE INDEX IF NOT EXISTS idx_tenant_host_chain ON platform.tenant_host (chain_id);
