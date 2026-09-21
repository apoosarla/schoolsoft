-- ----------------------------------------------------------------------------
-- A school is opened, not merely inserted.
--
-- Until now `POST /v1/tenancy/schools` was one INSERT, and everything that
-- makes a school usable -- a campus, a current academic year, terms, grades,
-- sections, subjects, somebody who can sign in -- was left to whoever
-- remembered the order. Miss the academic year and every date-scoped read
-- answers empty with nothing saying why; miss the last one and the school is
-- handed over with no way in.
--
-- Two things land here. `lifecycle` says whether the school has been opened,
-- and `school_onboarding_skip` records the setup step a school decided does
-- not apply to it, with the reason.
--
-- What is deliberately NOT here: a per-step "done" flag. Whether a school has
-- sections is a question about sections, and a flag beside the answer is a
-- second copy that drifts the first time somebody deletes a row. Readiness is
-- derived -- see `tenancy.internal.OnboardingStep`, which holds the one copy
-- of each question.
-- ----------------------------------------------------------------------------

-- Existing schools are open and have been for months; only a school created
-- from here on starts closed, and the create says so explicitly.
ALTER TABLE school ADD COLUMN IF NOT EXISTS lifecycle TEXT NOT NULL DEFAULT 'live'
    CHECK (lifecycle IN ('draft', 'live', 'suspended'));
ALTER TABLE school ADD COLUMN IF NOT EXISTS went_live_at TIMESTAMPTZ;

COMMENT ON COLUMN school.lifecycle IS
    'draft = being set up, never opened. live = opened. suspended = closed by '
    'the operator. Distinct from is_active, which is the row''s own soft delete.';

-- ----------------------------------------------------------------------------
-- A step a school chose not to do, and why.
--
-- Only the optional steps are skippable: a school with no sections cannot open
-- whatever reason it offers, so `school.onboard` refuses that skip rather than
-- storing a waiver that would make the refusal look arbitrary later. What this
-- table is for is the school that bills centrally, or runs the chain's theme,
-- and should not be asked again every time somebody opens the checklist.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS school_onboarding_skip (
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    step_key        TEXT NOT NULL,
    reason          TEXT NOT NULL,
    skipped_by_staff_id UUID REFERENCES staff(id),
    skipped_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (school_id, step_key)
);

-- A new table carrying school_id needs its own policy, or an id-enumeration
-- read of another school's row inside the same chain answers it. Same block as
-- V021/V022/V025: it covers any table here that has the column and no policy.
DO $$
DECLARE
    t TEXT;
BEGIN
    FOR t IN
        SELECT c.table_name FROM information_schema.columns c
        WHERE c.table_schema = current_schema()
          AND c.column_name = 'school_id'
          AND c.table_name = 'school_onboarding_skip'
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

-- ----------------------------------------------------------------------------
-- Who opens a school.
--
-- The heads, by the same argument as every other structural permission: the
-- people who build the school's grades and sections are the people who decide
-- it is ready. Kept off the coordinator and the registrar -- neither can create
-- a grade, so neither should be able to declare the ladder finished.
--
-- The other holder is not here and cannot be: a chain admin holds no staff_role
-- row at any school, so their grant lives in `PermissionChecker`. See the
-- comment there for why this one write is theirs.
-- ----------------------------------------------------------------------------
INSERT INTO role_perm (role_code, perm_code)
SELECT r.code, 'school.onboard'
FROM (VALUES ('principal'), ('vice_principal'), ('it_admin')) AS r(code)
ON CONFLICT DO NOTHING;
