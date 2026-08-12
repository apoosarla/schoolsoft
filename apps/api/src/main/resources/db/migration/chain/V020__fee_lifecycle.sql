-- ============================================================================
-- Phase 4 — fee engine completion. Closes GAP-09, the fee half of GAP-22
-- (library charges) and the fee half of GAP-30 (transport fees).
--
-- Money is the part of a school system that has to be right twice: right for
-- the parent looking at a bill, and right for the accountant reconciling a
-- day's collection. Everything below writes to the same double-entry ledger
-- V005 already established, so the two views cannot drift.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Job runs. The codebase's only scheduled work was the outbox drainer; from
-- here on, invoice generation, dunning and late fees are jobs, and Phases 6-8
-- add three more. A run row is what makes them restartable and auditable:
-- the same run key twice is one run, not two.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_run (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID REFERENCES school(id) ON DELETE CASCADE,
    job_name        TEXT NOT NULL,                    -- 'fee.generate' | 'fee.dunning'
    run_key         TEXT NOT NULL,                    -- idempotency key, unique per school+job
    state           TEXT NOT NULL DEFAULT 'running'
                      CHECK (state IN ('running','completed','failed')),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    stats           JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message   TEXT,
    UNIQUE NULLS NOT DISTINCT (school_id, job_name, run_key)
);
CREATE INDEX IF NOT EXISTS job_run_lookup_idx ON job_run (job_name, state, started_at DESC);

-- ----------------------------------------------------------------------------
-- Families (GAP-09). A sibling concession and a combined bill both need a
-- notion of "these children are one household" that outlives any single
-- guardian link.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS family (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    code            TEXT NOT NULL,
    name            TEXT NOT NULL,
    primary_guardian_id UUID REFERENCES guardian(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (school_id, code)
);

ALTER TABLE student
    ADD COLUMN IF NOT EXISTS family_id UUID REFERENCES family(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS student_family_idx ON student (family_id);

-- Sibling policy: the nth child of a family pays a percentage less. Ordering
-- is by admission date, so the youngest joining does not re-price the eldest.
CREATE TABLE IF NOT EXISTS sibling_concession_policy (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    academic_year_id UUID NOT NULL REFERENCES academic_year(id) ON DELETE CASCADE,
    nth_child       INT NOT NULL CHECK (nth_child >= 2),
    pct             NUMERIC(5,2) NOT NULL CHECK (pct > 0 AND pct <= 100),
    applies_to_head_id UUID REFERENCES fee_head(id),   -- NULL = every head
    UNIQUE (school_id, academic_year_id, nth_child)
);

-- ----------------------------------------------------------------------------
-- Invoice generation runs (FEE-02). Idempotent on (school, AY, cycle): the
-- second run of "October 2026" writes nothing, which is what makes a retry
-- after a partial failure safe.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fee_schedule_run (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    academic_year_id UUID NOT NULL REFERENCES academic_year(id) ON DELETE CASCADE,
    cycle_label     TEXT NOT NULL,
    grade_id        UUID REFERENCES grade(id) ON DELETE CASCADE,   -- NULL = whole school
    due_on          DATE NOT NULL,
    state           TEXT NOT NULL DEFAULT 'completed'
                      CHECK (state IN ('running','completed','failed')),
    invoices_created INT NOT NULL DEFAULT 0,
    students_skipped INT NOT NULL DEFAULT 0,
    total_billed    NUMERIC(14,2) NOT NULL DEFAULT 0,
    run_by_staff_id UUID REFERENCES staff(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (school_id, academic_year_id, cycle_label, grade_id)
);

ALTER TABLE fee_invoice
    ADD COLUMN IF NOT EXISTS fee_schedule_run_id UUID REFERENCES fee_schedule_run(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS family_id UUID REFERENCES family(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS academic_year_id UUID REFERENCES academic_year(id),
    -- Credit left on account after an overpayment, so a second payer's money is
    -- held rather than silently over-crediting the bill (FEE-17).
    ADD COLUMN IF NOT EXISTS advance_amount NUMERIC(12,2) NOT NULL DEFAULT 0;

-- A combined family invoice bills a household rather than one child, so the
-- student is optional as long as a family is named.
ALTER TABLE fee_invoice ALTER COLUMN student_id DROP NOT NULL;
ALTER TABLE fee_invoice
    DROP CONSTRAINT IF EXISTS fee_invoice_has_a_payer;
ALTER TABLE fee_invoice
    ADD CONSTRAINT fee_invoice_has_a_payer
        CHECK (student_id IS NOT NULL OR family_id IS NOT NULL);

-- One *generated* invoice per student per cycle. This is the constraint that
-- actually prevents double billing when a run is repeated; the run's
-- idempotency key is the friendly half of the same rule.
--
-- Deliberately scoped to generated invoices: an office raises ad-hoc bills
-- (a replacement book, a trip) against the same student and label more than
-- once, and a uniqueness rule there would only teach them to invent labels.
CREATE UNIQUE INDEX IF NOT EXISTS fee_invoice_one_per_student_cycle
    ON fee_invoice (student_id, cycle_label)
    WHERE student_id IS NOT NULL AND fee_schedule_run_id IS NOT NULL AND status <> 'cancelled';

ALTER TABLE fee_invoice_line
    ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'structure'
        CHECK (source IN ('structure','concession','sibling_concession','transport','adjustment','manual')),
    ADD COLUMN IF NOT EXISTS student_id UUID REFERENCES student(id) ON DELETE CASCADE;

-- ----------------------------------------------------------------------------
-- Adjustments (FEE-08/10/11, LIB-03/04). A cheque bounce is a reversal, not a
-- deleted payment; a fine is a charge with a reason; a waiver is somebody's
-- decision, recorded as such.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fee_adjustment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    fee_invoice_id  UUID NOT NULL REFERENCES fee_invoice(id) ON DELETE CASCADE,
    payment_id      UUID REFERENCES payment(id),      -- set for a reversal or refund
    kind            TEXT NOT NULL
                      CHECK (kind IN ('credit_note','refund','waiver','late_fee','charge','reversal')),
    amount          NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    reason          TEXT NOT NULL,
    approved_by_staff_id UUID REFERENCES staff(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS fee_adjustment_invoice_idx ON fee_adjustment (fee_invoice_id);

-- ----------------------------------------------------------------------------
-- Dunning (FEE-09/10). The cadence is per school because it is a relationship
-- decision, not a technical one: how many days after a due date a school is
-- willing to write to a family differs, and getting it wrong costs goodwill.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dunning_policy (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    grace_days      INT NOT NULL DEFAULT 0 CHECK (grace_days >= 0),
    reminder_days   INT[] NOT NULL DEFAULT '{1,7,15}',   -- days after due date
    late_fee_pct    NUMERIC(5,2),
    late_fee_flat   NUMERIC(12,2),
    late_fee_head_id UUID REFERENCES fee_head(id),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (school_id)
);

-- What has already been sent, so a reminder is not repeated on every run.
CREATE TABLE IF NOT EXISTS dunning_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    fee_invoice_id  UUID NOT NULL REFERENCES fee_invoice(id) ON DELETE CASCADE,
    kind            TEXT NOT NULL CHECK (kind IN ('overdue','reminder','late_fee')),
    day_offset      INT NOT NULL,                     -- days past due when raised
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (fee_invoice_id, kind, day_offset)
);

-- ----------------------------------------------------------------------------
-- Transport and library charges hang off their own records, so a fee line can
-- be traced back to the route ride or the overdue book that caused it.
-- ----------------------------------------------------------------------------
ALTER TABLE transport_route
    ADD COLUMN IF NOT EXISTS monthly_fee NUMERIC(12,2),
    ADD COLUMN IF NOT EXISTS fee_head_id UUID REFERENCES fee_head(id);

-- A replacement charge needs to know what the book was worth.
ALTER TABLE library_title
    ADD COLUMN IF NOT EXISTS price NUMERIC(10,2);

ALTER TABLE library_issue
    ADD COLUMN IF NOT EXISTS fine_per_day NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS fee_adjustment_id UUID REFERENCES fee_adjustment(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS charge_kind TEXT
        CHECK (charge_kind IS NULL OR charge_kind IN ('overdue_fine','lost','damaged'));

-- Library charge defaults per school (fine per day, replacement multiplier).
CREATE TABLE IF NOT EXISTS library_charge_policy (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    fine_per_day    NUMERIC(10,2) NOT NULL DEFAULT 1,
    max_fine        NUMERIC(10,2),
    lost_multiplier NUMERIC(5,2) NOT NULL DEFAULT 1,   -- × the title's price
    damaged_pct     NUMERIC(5,2) NOT NULL DEFAULT 50,
    fee_head_id     UUID REFERENCES fee_head(id),
    UNIQUE (school_id)
);

-- Ledger accounts the new postings need.
INSERT INTO ledger_account (code, name, type, tally_ledger_name) VALUES
  ('FEE_WAIVER',    'Fee Waivers',        'expense',  'Fee Waivers'),
  ('LATE_FEE',      'Late Fee Income',    'income',   'Late Fee Income'),
  ('LIBRARY_FINE',  'Library Fine Income','income',   'Library Fine Income'),
  ('ADVANCE',       'Fees Received in Advance', 'liability', 'Fees Received in Advance')
ON CONFLICT (code) DO NOTHING;
