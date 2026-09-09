-- ============================================================================
-- Phase 9 — student exit (GAP-03).
--
-- Until now a child left the school by having `enrolment.status` set to
-- 'withdrawn'. That is a word, not a workflow: it records no reason, no last
-- working date, nothing about the books and buses and money the family still
-- has open, and it produces no Transfer Certificate — which is the one artefact
-- the next school actually asks for, and which a school is required to issue.
--
-- Three tables:
--
--   withdrawal        the exit itself: why, when, and what state it is in
--   clearance_item    one row per area that has to be settled first
--   certificate       what was issued, frozen at issue and serially numbered
--
-- The state machine is deliberately small — a leaver is not a project — but it
-- is a state machine, because the interesting failure is a TC issued for a
-- child whose fees were never settled, and that is only preventable if
-- "cleared" is a state something asserts rather than a note somebody typed.
--
--   draft -> clearance_pending -> cleared -> completed
--                              \-> cancelled
--
-- `completed` is the point of no return: the enrolment closes, the bus seat is
-- released, and the child stops appearing on rosters from the last working day.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- `ends_on` is the last day an enrolment counts.
--
-- Every module answering "is this child here?" now asks it as a question about
-- a date — `enrolment.starts_on <= d AND (ends_on IS NULL OR ends_on >= d)` —
-- rather than about `status`, because a withdrawal filed on the 1st with a last
-- working day of the 30th has to leave the child on the register until the 30th
-- and take them off on the 1st of the next month, and a status column cannot
-- say that. See `enrolment/api/EnrolmentActivity.java`, which is the single
-- copy of the predicate.
--
-- That makes overlapping enrolments a real defect rather than a curiosity, and
-- `EnrolmentRepository.transfer` used to create one: it closed the outgoing row
-- at today and opened the incoming one at today, so on the day of a section
-- change the child stood on two registers. Existing rows are corrected here;
-- the code stops making them.
-- ----------------------------------------------------------------------------
UPDATE enrolment old
SET ends_on = old.ends_on - 1
FROM enrolment new
WHERE new.student_id = old.student_id
  AND new.id <> old.id
  AND old.ends_on IS NOT NULL
  AND new.starts_on = old.ends_on
  AND old.starts_on < old.ends_on;

-- A child who leaves has left, whatever the office called it. The status is now
-- the *reason* the enrolment closed, and the dates are the authority on whether
-- it is open.
COMMENT ON COLUMN enrolment.ends_on IS
    'Last day this enrolment counts, inclusive. NULL means open-ended. The '
    'active-on-date predicate reads this, not status.';

-- ----------------------------------------------------------------------------
-- withdrawal
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS withdrawal (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    enrolment_id    UUID NOT NULL REFERENCES enrolment(id) ON DELETE CASCADE,

    -- Why. The code is what the TC prints and what a leavers report groups on;
    -- the free text is what the registrar will actually want to read back.
    reason_code     TEXT NOT NULL
                      CHECK (reason_code IN ('transfer_out','relocation','financial',
                                             'medical','disciplinary','graduation','other')),
    reason          TEXT NOT NULL CHECK (length(btrim(reason)) > 0),

    requested_on    DATE NOT NULL DEFAULT CURRENT_DATE,
    -- The last day the child attends. Everything downstream keys off this, not
    -- off the day the paperwork was done.
    last_working_date DATE NOT NULL,

    state           TEXT NOT NULL DEFAULT 'clearance_pending'
                      CHECK (state IN ('draft','clearance_pending','cleared','completed','cancelled')),

    -- An override is the school saying "let them go anyway". It is a named
    -- person and a reason or it does not happen — a leaver waved through over
    -- ₹40,000 of arrears is exactly the decision somebody disputes later.
    dues_override_reason      TEXT,
    dues_override_by_staff_id UUID REFERENCES staff(id),
    dues_override_at          TIMESTAMPTZ,

    initiated_by_staff_id UUID REFERENCES staff(id),
    completed_by_staff_id UUID REFERENCES staff(id),
    completed_at    TIMESTAMPTZ,
    cancelled_reason TEXT,
    cancelled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CHECK (last_working_date >= requested_on - 365),
    CHECK (dues_override_reason IS NULL OR length(btrim(dues_override_reason)) > 0)
);

-- One live withdrawal per student. A second one filed while the first is open
-- is a mistake at the counter, not a second departure.
CREATE UNIQUE INDEX IF NOT EXISTS withdrawal_one_open_per_student
    ON withdrawal (student_id) WHERE state IN ('draft','clearance_pending','cleared');

CREATE INDEX IF NOT EXISTS withdrawal_school_state_idx ON withdrawal (school_id, state);
CREATE INDEX IF NOT EXISTS withdrawal_enrolment_idx ON withdrawal (enrolment_id);

-- ----------------------------------------------------------------------------
-- clearance_item
--
-- Carries its own school_id rather than borrowing the parent's. A child table
-- without one has no row-level security policy and is then only as safe as the
-- next author remembering to join the covered parent — which is the bug the
-- 2026-09-09 audit found thirteen times.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clearance_item (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    withdrawal_id   UUID NOT NULL REFERENCES withdrawal(id) ON DELETE CASCADE,
    area            TEXT NOT NULL CHECK (area IN ('fees','library','transport','assets')),

    -- pending  nothing to settle yet, or waiting on a human (assets)
    -- blocked  something is outstanding and completion is refused
    -- cleared  settled, by payment/return/there being nothing owed
    -- waived   deliberately let go, with a reason and a name attached
    state           TEXT NOT NULL DEFAULT 'pending'
                      CHECK (state IN ('pending','blocked','cleared','waived')),

    -- What is outstanding, in the area's own terms: an amount for fees, a copy
    -- count for the library. Held so the checklist reads without re-probing.
    detail          TEXT,
    amount          NUMERIC(12,2),

    checked_at      TIMESTAMPTZ,
    resolved_by_staff_id UUID REFERENCES staff(id),
    resolved_reason TEXT,
    resolved_at     TIMESTAMPTZ,

    UNIQUE (withdrawal_id, area),
    CHECK (state <> 'waived' OR length(btrim(coalesce(resolved_reason, ''))) > 0)
);

CREATE INDEX IF NOT EXISTS clearance_item_withdrawal_idx ON clearance_item (withdrawal_id);

-- ----------------------------------------------------------------------------
-- certificate
--
-- `payload` is the certificate, not a cache of it. A TC states the child's
-- attendance and class and conduct *as at the date of issue*; recomputing those
-- fields a year later from live tables would produce a different document under
-- the same serial number, which is the one thing a certificate may not do. So
-- the fields are frozen at issue and hashed, and `GET /verify` re-hashes the
-- stored payload to say whether anybody has been at the row since.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS certificate (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    enrolment_id    UUID REFERENCES enrolment(id) ON DELETE SET NULL,
    withdrawal_id   UUID REFERENCES withdrawal(id) ON DELETE SET NULL,

    kind            TEXT NOT NULL
                      CHECK (kind IN ('transfer','leaving','transcript','bonafide')),
    serial_no       TEXT NOT NULL,
    issued_on       DATE NOT NULL DEFAULT CURRENT_DATE,
    issued_by_staff_id UUID REFERENCES staff(id),

    -- `json`, not `jsonb`, and that is the whole point: jsonb normalises key
    -- order and whitespace, so a payload written and read back is equal as data
    -- but different as bytes — and the hash below is over bytes. `json` stores
    -- the text verbatim, which is what lets `/verify` recompute the digest years
    -- later and get the same answer.
    payload         JSON NOT NULL,
    payload_hash    TEXT NOT NULL,

    -- A certificate is never edited and never deleted: a wrong one is revoked
    -- and a new one issued, so the serial that was handed to a family still
    -- resolves and still says what it said.
    revoked_at      TIMESTAMPTZ,
    revoked_reason  TEXT,
    revoked_by_staff_id UUID REFERENCES staff(id),
    superseded_by_id UUID REFERENCES certificate(id) ON DELETE SET NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (school_id, kind, serial_no),
    CHECK (revoked_at IS NULL OR length(btrim(coalesce(revoked_reason, ''))) > 0)
);

-- Duplicate prevention (XFER-07): one live certificate of a kind per enrolment.
-- Revoking the first is what makes room for a reissue, which is the only way a
-- school should be correcting one.
CREATE UNIQUE INDEX IF NOT EXISTS certificate_one_live_per_enrolment
    ON certificate (enrolment_id, kind) WHERE revoked_at IS NULL AND enrolment_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS certificate_student_idx ON certificate (student_id);
CREATE INDEX IF NOT EXISTS certificate_school_kind_idx ON certificate (school_id, kind);

-- A certificate is append-only in the same sense the ledger is: the row that
-- was handed to a family cannot quietly become a different row. Revocation and
-- supersession are the two columns an UPDATE may touch.
CREATE OR REPLACE FUNCTION certificate_is_append_only() RETURNS TRIGGER AS $$
BEGIN
    -- `payload::text`, because `json` has no equality operator in Postgres —
    -- comparing the columns directly raises "operator does not exist: json = json"
    -- and the trigger fails open on the very edit it exists to refuse.
    IF NEW.payload::text IS DISTINCT FROM OLD.payload::text
       OR NEW.payload_hash IS DISTINCT FROM OLD.payload_hash
       OR NEW.serial_no IS DISTINCT FROM OLD.serial_no
       OR NEW.kind IS DISTINCT FROM OLD.kind
       OR NEW.student_id IS DISTINCT FROM OLD.student_id
       OR NEW.issued_on IS DISTINCT FROM OLD.issued_on THEN
        RAISE EXCEPTION 'A certificate is issued once. Revoke it and issue a new one.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS certificate_append_only ON certificate;
CREATE TRIGGER certificate_append_only
    BEFORE UPDATE ON certificate
    FOR EACH ROW EXECUTE FUNCTION certificate_is_append_only();

-- ----------------------------------------------------------------------------
-- Grants.
--
-- Withdrawal is registrar work; the heads hold it too because they are who a
-- disputed exit escalates to. `withdrawal.override` is deliberately *not* the
-- registrar's: letting a family leave owing money is a decision about money,
-- and the person who processes the exit should not also be the person who
-- forgives its arrears. Heads and the accountant hold it.
--
-- `certificate.issue` is narrower still. A TC is a statutory document with the
-- school's name on it.
-- ----------------------------------------------------------------------------
INSERT INTO role_perm (role_code, perm_code)
SELECT r.code, p.perm
FROM (VALUES ('principal'), ('vice_principal'), ('it_admin')) AS r(code)
CROSS JOIN (VALUES
    ('withdrawal.view'), ('withdrawal.manage'), ('withdrawal.override'),
    ('certificate.view'), ('certificate.issue'), ('certificate.revoke')
) AS p(perm)
ON CONFLICT DO NOTHING;

INSERT INTO role_perm (role_code, perm_code)
SELECT 'registrar', p.perm FROM (VALUES
    ('withdrawal.view'), ('withdrawal.manage'),
    ('certificate.view'), ('certificate.issue')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- The accountant sees the exits queue because arrears are why one stalls, and
-- holds the override because forgiving them is their call.
INSERT INTO role_perm (role_code, perm_code)
SELECT 'accountant', p.perm FROM (VALUES
    ('withdrawal.view'), ('withdrawal.override')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- The counter is asked "has the TC come through yet?" and should be able to
-- answer without being able to issue one.
INSERT INTO role_perm (role_code, perm_code)
SELECT 'front_office', p.perm FROM (VALUES
    ('withdrawal.view'), ('certificate.view')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- The librarian resolves the library line of somebody else's checklist.
INSERT INTO role_perm (role_code, perm_code)
SELECT 'librarian', p.perm FROM (VALUES
    ('withdrawal.view')
) AS p(perm)
ON CONFLICT DO NOTHING;

-- admin-web's Exits screen: the withdrawals queue, its checklists, and the
-- certificates issued off them.
UPDATE role
SET screen_keys = array_append(screen_keys, 'exits')
WHERE code IN ('principal', 'vice_principal', 'it_admin', 'registrar', 'front_office')
  AND NOT ('exits' = ANY(screen_keys));

-- ----------------------------------------------------------------------------
-- RLS for the tables added above — the same sweep V022 and V025 used, so a new
-- school-scoped table is protected by default rather than by the next author
-- remembering.
-- ----------------------------------------------------------------------------
DO $$
DECLARE t TEXT;
BEGIN
    FOR t IN
        SELECT c.table_name FROM information_schema.columns c
        WHERE c.table_schema = current_schema()
          AND c.column_name = 'school_id'
          AND c.table_name <> 'school'
          AND c.table_name IN (SELECT table_name FROM information_schema.tables
                               WHERE table_schema = current_schema() AND table_type = 'BASE TABLE')
          AND NOT EXISTS (
              SELECT 1 FROM pg_policies p
              WHERE p.schemaname = current_schema() AND p.tablename = c.table_name)
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I_school_isolation ON %I
               USING (is_trusted_session() OR school_id = current_school_id() OR current_school_id() IS NULL)
               WITH CHECK (is_trusted_session() OR school_id = current_school_id() OR current_school_id() IS NULL)',
            t, t);
    END LOOP;
END $$;
