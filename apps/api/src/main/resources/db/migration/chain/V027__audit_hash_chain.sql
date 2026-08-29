-- Makes the audit log tamper-evident.
--
-- `audit_log` was an ordinary table. Nothing recorded the order of its rows
-- beyond a sequence, so a row could be edited or removed and the log would
-- still read as consistent — which is the one property an audit log has to
-- have. This is children's data and role grants.
--
-- Each row now carries the hash of the row before it. Altering any field of
-- any row, or removing a row, breaks every link after it, and
-- `AuditChainVerifier` finds the first break.
--
-- The chain is computed by a trigger rather than by the application on
-- purpose: the guarantee has to hold for every writer, including a script with
-- a psql prompt, and an application that has to remember to hash is an
-- application that will one day forget.
--
-- What this is NOT: prevention. Anyone who can drop this trigger can forge a
-- chain. Tamper-evidence means somebody reading the log can tell — which is
-- the achievable property, and the one an auditor actually asks for.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS prev_hash  TEXT;
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS entry_hash TEXT;

-- The row's contents, flattened for hashing. A unit separator (U+001F) joins
-- the fields so that a value containing the separator cannot be constructed to
-- imitate a different set of field boundaries.
CREATE OR REPLACE FUNCTION audit_log_payload(r audit_log) RETURNS TEXT AS $$
    SELECT concat_ws(
        E'\x1F',
        r.id::text,
        coalesce(r.school_id::text, ''),
        coalesce(r.actor_user_id::text, ''),
        r.action,
        coalesce(r.target_type, ''),
        coalesce(r.target_id::text, ''),
        coalesce(r.before_state::text, ''),
        coalesce(r.after_state::text, ''),
        coalesce(r.reason, ''),
        coalesce(r.request_payload::text, ''),
        -- Pinned rendering, not ::text: ::text follows the session's DateStyle
        -- and TimeZone, so the same row would hash differently depending on
        -- who wrote it and who is checking it.
        to_char(r.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US')
    );
$$ LANGUAGE sql IMMUTABLE;

CREATE OR REPLACE FUNCTION audit_log_chain() RETURNS TRIGGER AS $$
DECLARE
    last_hash TEXT;
BEGIN
    -- Two concurrent appends that both read the same tail would fork the
    -- chain, and a forked chain is indistinguishable from a tampered one. The
    -- advisory lock serialises appends for the rest of the transaction. Audit
    -- writes are low-volume by nature — this is not the hot path.
    PERFORM pg_advisory_xact_lock(hashtext(current_schema() || '.audit_log'));

    SELECT entry_hash INTO last_hash
    FROM audit_log
    ORDER BY id DESC
    LIMIT 1;

    NEW.prev_hash := last_hash;
    NEW.entry_hash := encode(
        digest(coalesce(last_hash, '') || E'\x1F' || audit_log_payload(NEW), 'sha256'),
        'hex');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Backfill whatever is already there, in insertion order, so an existing log
-- becomes a valid chain from its first row rather than starting a second one.
DO $$
DECLARE
    r         audit_log%ROWTYPE;
    last_hash TEXT := NULL;
BEGIN
    FOR r IN SELECT * FROM audit_log ORDER BY id LOOP
        UPDATE audit_log
        SET prev_hash = last_hash,
            entry_hash = encode(
                digest(coalesce(last_hash, '') || E'\x1F' || audit_log_payload(r), 'sha256'),
                'hex')
        WHERE id = r.id
        RETURNING entry_hash INTO last_hash;
    END LOOP;
END $$;

ALTER TABLE audit_log ALTER COLUMN entry_hash SET NOT NULL;

DROP TRIGGER IF EXISTS audit_log_chain_trg ON audit_log;
CREATE TRIGGER audit_log_chain_trg
    BEFORE INSERT ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_chain();

-- An UPDATE is never legitimate: the log records what happened, and what
-- happened does not change. Blocking it here means a tampering attempt fails
-- loudly instead of quietly breaking the chain for somebody to find later.
-- DELETE is deliberately left alone — retention policy needs it, and the chain
-- is what makes a deletion visible.
CREATE OR REPLACE FUNCTION audit_log_is_append_only() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: row % may not be updated', OLD.id;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS audit_log_no_update_trg ON audit_log;
CREATE TRIGGER audit_log_no_update_trg
    BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_is_append_only();
