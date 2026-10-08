-- ============================================================================
-- What Schoolsoft's own operators did, kept where no customer's schema is.
--
-- A platform admin signs in through a door of their own and works above every
-- chain: provisions one, opens a school in it, reads its headcount and its fee
-- total. None of that left a trail. `audit_log` lives inside each chain's
-- schema, and an operator's request stands in `platform`, which had nowhere to
-- write — so the people with the widest reach were the only ones nobody could
-- ask "who looked at this chain, and when" about (GAP-34, SEC-06).
--
-- One row per request an operator made, whatever it was and however it ended:
-- a read into a chain is recorded the same as a write, because reading another
-- company's numbers is the act a customer asks about, and a refused request is
-- recorded with its status, because the attempt is the interesting part.
--
-- `chain_id` carries no foreign key on purpose. The trail has to outlive the
-- chain it is about — offboarding a customer must not take with it the record
-- of what was done to them.
-- ============================================================================

CREATE TABLE IF NOT EXISTS platform.operator_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    actor_user_id   UUID NOT NULL REFERENCES platform.platform_user(id),
    -- The verb and the route as mapped, e.g. 'GET /v1/platform-admin/chains/{id}/stats':
    -- what kind of act it was, groupable without parsing ids out of a path.
    action          TEXT NOT NULL,
    -- The path as asked, ids and query included: which chain, which school.
    path            TEXT NOT NULL,
    chain_id        UUID,
    request_payload JSONB,
    status          INT NOT NULL,
    ip_address      INET,
    user_agent      TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS operator_audit_log_chain_idx
    ON platform.operator_audit_log (chain_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS operator_audit_log_actor_idx
    ON platform.operator_audit_log (actor_user_id, occurred_at DESC);

-- The log records what happened, and what happened does not change. Neither an
-- UPDATE nor a DELETE is ever legitimate here: unlike a chain's `audit_log`
-- there is no hash chain to make a removal visible, so a removal is refused
-- instead. TRUNCATE is left alone — it is what a fixture uses, and it cannot
-- be mistaken for an edit.
CREATE OR REPLACE FUNCTION platform.operator_audit_log_is_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'operator_audit_log is append-only: row % may not be changed or removed', OLD.id;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS operator_audit_log_append_only_trg ON platform.operator_audit_log;
CREATE TRIGGER operator_audit_log_append_only_trg
    BEFORE UPDATE OR DELETE ON platform.operator_audit_log
    FOR EACH ROW EXECUTE FUNCTION platform.operator_audit_log_is_append_only();
