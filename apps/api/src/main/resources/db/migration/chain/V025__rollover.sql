-- ============================================================================
-- Phase 6 — year closure and rollover (GAP-02).
--
-- Moving a school from one academic year to the next is the single largest
-- thing the product does, and the one with the least tolerance for a half
-- finish: a run that stops in the middle has already moved some children and
-- not others. So the shape here is a *plan that is executed*, not a button:
--
--   rollover_run          one attempt, with a state machine and a run key
--   rollover_allocation   one row per child — where they are going and why,
--                         editable before commit, marked `applied` after
--   rollover_artifact     everything the run created, so a roll-back deletes
--                         exactly that and nothing a human did afterwards
--
-- Allocation rows are what make the run restartable: commit walks the planned
-- ones in batches and marks each applied, so resuming after an interruption
-- skips the children already moved rather than enrolling them twice.
-- ============================================================================

-- A detained child's old enrolment is not 'promoted'. Saying so plainly keeps
-- the history readable a year later, when somebody asks why they repeated.
ALTER TABLE enrolment DROP CONSTRAINT IF EXISTS enrolment_status_check;
ALTER TABLE enrolment
    ADD CONSTRAINT enrolment_status_check
    CHECK (status IN ('active','withdrawn','transferred','graduated','promoted','detained'));

-- An arrear that moved into next year's opening balance is not written off and
-- not still outstanding — it is somewhere else. Without its own status the
-- amount is counted twice: once on the old invoice, once on the new one.
ALTER TABLE fee_invoice DROP CONSTRAINT IF EXISTS fee_invoice_status_check;
ALTER TABLE fee_invoice
    ADD CONSTRAINT fee_invoice_status_check
    CHECK (status IN ('draft','open','partial','paid','overdue','cancelled','refunded','carried_forward'));

-- Which section of last year a cloned section came from. The allocation step
-- reads it to keep 5A's children together in 6A by default, and the clone is
-- idempotent because of it.
ALTER TABLE section
    ADD COLUMN IF NOT EXISTS source_section_id UUID REFERENCES section(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS section_source_idx ON section (source_section_id);

CREATE TABLE IF NOT EXISTS rollover_run (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    from_academic_year_id UUID NOT NULL REFERENCES academic_year(id),
    to_academic_year_id   UUID NOT NULL REFERENCES academic_year(id),
    -- Idempotency key. The same key twice is the same run, which is what makes
    -- a retry after a crash safe rather than a second cohort of enrolments.
    run_key         TEXT NOT NULL,
    state           TEXT NOT NULL DEFAULT 'draft'
                      CHECK (state IN ('draft','structure_cloned','allocated','committed','rolled_back')),
    batch_size      INT NOT NULL DEFAULT 200 CHECK (batch_size > 0),
    batches_done    INT NOT NULL DEFAULT 0,
    stats           JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_by_staff_id UUID REFERENCES staff(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at    TIMESTAMPTZ,
    rolled_back_at  TIMESTAMPTZ,
    CHECK (from_academic_year_id <> to_academic_year_id),
    UNIQUE (school_id, run_key)
);

-- One rollover in flight per school. Two open runs would allocate the same
-- children into two different sections and both would think they were right.
CREATE UNIQUE INDEX IF NOT EXISTS rollover_run_one_open_per_school
    ON rollover_run (school_id) WHERE state IN ('draft','structure_cloned','allocated');

CREATE TABLE IF NOT EXISTS rollover_allocation (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rollover_run_id UUID NOT NULL REFERENCES rollover_run(id) ON DELETE CASCADE,
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    from_enrolment_id UUID NOT NULL REFERENCES enrolment(id) ON DELETE CASCADE,
    from_section_id UUID NOT NULL REFERENCES section(id) ON DELETE CASCADE,
    decision        TEXT NOT NULL CHECK (decision IN ('promote','detain','graduate')),
    -- Null for a graduate, and for a child the allocator could not place: an
    -- unplaced row is a question for the school, not a reason to guess.
    to_section_id   UUID REFERENCES section(id) ON DELETE SET NULL,
    roll_no         TEXT,
    over_capacity_reason TEXT,
    state           TEXT NOT NULL DEFAULT 'planned'
                      CHECK (state IN ('planned','applied','skipped')),
    note            TEXT,
    new_enrolment_id UUID REFERENCES enrolment(id) ON DELETE SET NULL,
    batch_no        INT NOT NULL DEFAULT 0,
    applied_at      TIMESTAMPTZ,
    UNIQUE (rollover_run_id, student_id)
);
CREATE INDEX IF NOT EXISTS rollover_allocation_run_state_idx
    ON rollover_allocation (rollover_run_id, state, batch_no);

-- What the run created, row by row. A roll-back reads this rather than
-- re-deriving what it "would have" made: by then a human may have added a
-- payment against the opening balance or moved a child by hand, and deleting
-- from a guess would take their work with it. Carry-forwards added later
-- (health records, documents) need no new columns here.
CREATE TABLE IF NOT EXISTS rollover_artifact (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rollover_run_id UUID NOT NULL REFERENCES rollover_run(id) ON DELETE CASCADE,
    rollover_allocation_id UUID REFERENCES rollover_allocation(id) ON DELETE CASCADE,
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    kind            TEXT NOT NULL,          -- 'enrolment' | 'fee_invoice' | 'student_transport' | 'student_subject'
    row_id          UUID NOT NULL,
    -- For a row the run *changed* rather than created: what it said before, so
    -- the roll-back puts back that value instead of a plausible one.
    prior_state     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (rollover_run_id, kind, row_id)
);

-- The screen the wizard lives behind. Rollover moves every child in the school
-- and closes the year behind them, so it sits with the office roles that own
-- the decision — never with a teaching role.
UPDATE role
SET screen_keys = array_append(screen_keys, 'rollover')
WHERE code IN ('principal', 'vice_principal', 'it_admin', 'registrar')
  AND NOT ('rollover' = ANY(screen_keys));

-- ----------------------------------------------------------------------------
-- RLS for the tables added above, by the same sweep Phases 3 and 5 used.
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
