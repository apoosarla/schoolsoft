-- ----------------------------------------------------------------------------
-- The DPDP data lifecycle: consent a family can give and take back, and the
-- two requests they can make about what the school holds on their child
-- (SEC-09, GAP-23).
--
-- `consent_record` has existed since V008 with nothing writing to it. This
-- gives it a shape somebody can rely on, and adds the request beside it.
-- ----------------------------------------------------------------------------

-- Consent is a standing answer: a row with no `revoked_at` says yes, and
-- withdrawing it closes the row rather than deleting it — when a family asks
-- "when did we agree to that, and when did we stop", both dates are the
-- answer. One standing row per subject and purpose, so granting twice is a
-- retry and not a second consent.
ALTER TABLE consent_record
    ADD COLUMN IF NOT EXISTS recorded_by_user_id UUID REFERENCES user_account(id);

CREATE UNIQUE INDEX IF NOT EXISTS consent_one_standing
    ON consent_record (subject_type, subject_id, purpose) WHERE revoked_at IS NULL;

-- When a person's details were removed at their own request. The row stays:
-- the ledger, the register and the certificates still hang off its id, and a
-- school is obliged to keep those. What goes is who it was.
ALTER TABLE student  ADD COLUMN IF NOT EXISTS erased_at TIMESTAMPTZ;
ALTER TABLE guardian ADD COLUMN IF NOT EXISTS erased_at TIMESTAMPTZ;

-- A request a family made about their child's data. `due_on` is fixed when it
-- is filed — the statutory clock starts at the request, not at whenever the
-- office first opens the queue — so "what is overdue" is a comparison of two
-- dates and not a judgement.
CREATE TABLE IF NOT EXISTS data_request (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id            UUID NOT NULL REFERENCES school(id),
    student_id           UUID NOT NULL REFERENCES student(id),
    kind                 TEXT NOT NULL CHECK (kind IN ('access','erasure')),
    status               TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','fulfilled','refused')),
    requested_by_user_id UUID NOT NULL REFERENCES user_account(id),
    note                 TEXT,
    requested_on         DATE NOT NULL,
    due_on               DATE NOT NULL,
    decided_at           TIMESTAMPTZ,
    decided_by_user_id   UUID REFERENCES user_account(id),
    decision_reason      TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'open') = (decided_at IS NULL))
);

-- Asking twice while the first is still open is the same request.
CREATE UNIQUE INDEX IF NOT EXISTS data_request_one_open
    ON data_request (student_id, kind) WHERE status = 'open';
CREATE INDEX IF NOT EXISTS data_request_queue_idx ON data_request (school_id, status, due_on);

-- A table with a school_id needs its own policy; V009's loop ran long before
-- this table existed.
ALTER TABLE data_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE data_request FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS data_request_school_isolation ON data_request;
CREATE POLICY data_request_school_isolation ON data_request
    USING (is_trusted_session() OR school_id = current_school_id() OR current_school_id() IS NULL)
    WITH CHECK (is_trusted_session() OR school_id = current_school_id() OR current_school_id() IS NULL);

-- Serving a request is the head's and the registrar's: the registrar keeps the
-- student record, and an erasure is not something to hand further down. A
-- family's own half (`privacy.own`) is theirs by baseline, not by grant.
INSERT INTO role_perm (role_code, perm_code)
SELECT r.code, 'privacy.manage'
FROM (VALUES ('principal'), ('registrar')) AS r(code)
ON CONFLICT DO NOTHING;
