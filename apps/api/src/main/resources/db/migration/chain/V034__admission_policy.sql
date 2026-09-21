-- ----------------------------------------------------------------------------
-- The funnel each school actually runs, plus three cleanups the module has been
-- carrying since V007.
--
-- 1. admission_policy — not every school sets an entrance test. A primary
--    intake interviews the parents and offers a seat; a senior-school intake
--    tests. Until now `admission_transition` allowed both routes out of
--    `review` and nothing said which one *this* school uses, so the office
--    could schedule a test at a school that runs none, and could skip the test
--    at a school that requires one. The policy answers "which funnel", and the
--    transition rows say which moves belong to which.
--
-- 2. offer_expires_on was read everywhere and written nowhere: the column, the
--    DTO and the public tracking page's "Offer valid until ..." all existed
--    while the value stayed NULL for every application ever created. It is now
--    derived from the school's offer window when an offer is made, which is
--    also what a lapse job will later read.
--
-- 3. admission_application.documents was loose JSONB that no code has ever read
--    or written. Dropped rather than left as a second, empty source of truth
--    next to the document store that replaces it.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS admission_policy (
    school_id             UUID PRIMARY KEY REFERENCES school(id) ON DELETE CASCADE,
    -- TRUE keeps the funnel every existing school is already running, so this
    -- migration changes no school's behaviour on the day it lands. A school
    -- that does not test turns it off from the admissions settings screen.
    entrance_test_required BOOLEAN NOT NULL DEFAULT TRUE,
    -- How long an offer stands. Counted in days from the day it is made; the
    -- date lands on the application so the family can see it and so an expiry
    -- job has something to act on.
    offer_validity_days   INT NOT NULL DEFAULT 14 CHECK (offer_validity_days > 0),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
    -- No `version`: a settings row is edited occasionally by one person, which
    -- is the case V028 gives for leaving a table unversioned.
);

-- Every school that exists today keeps the funnel it has been running. A school
-- provisioned *after* this migration has no row until somebody saves one, and
-- reads fall back to these same defaults -- see AdmissionsRepository.policy,
-- which is why a missing row is not an error.
INSERT INTO admission_policy (school_id)
SELECT id FROM school
ON CONFLICT (school_id) DO NOTHING;

-- ----------------------------------------------------------------------------
-- Which moves belong to which funnel.
--
-- Three-valued on purpose: NULL means the move is legal either way, TRUE that
-- it only exists at a school that tests, FALSE that it only exists at a school
-- that does not. So `review -> test_scheduled` disappears for a school with no
-- entrance test, and `review -> offered` disappears for a school with one --
-- and a school cannot quietly skip its own assessment step.
-- ----------------------------------------------------------------------------
ALTER TABLE admission_transition ADD COLUMN IF NOT EXISTS requires_entrance_test BOOLEAN;

UPDATE admission_transition SET requires_entrance_test = TRUE
 WHERE (from_state, to_state) IN (
    ('review',         'test_scheduled'),
    ('test_scheduled', 'test_done'),
    ('test_scheduled', 'review'),
    ('test_done',      'test_scheduled'),
    ('test_done',      'offered'),
    ('test_done',      'waitlist')
 );

-- The one move that only a school without a test may make: straight from
-- review to an offer. A school that tests reaches `offered` through test_done.
UPDATE admission_transition SET requires_entrance_test = FALSE
 WHERE (from_state, to_state) = ('review', 'offered');

-- ----------------------------------------------------------------------------
-- The unused column. Provably unused: nothing in the codebase reads or writes
-- it, and every row in every environment still holds its '[]' default.
-- ----------------------------------------------------------------------------
ALTER TABLE admission_application DROP COLUMN IF EXISTS documents;

-- ----------------------------------------------------------------------------
-- Application numbers come from the school's series like every other number
-- (see V019). The public site was minting "WEB-" + a random UUID fragment and
-- the office was typing them by hand, so two applications could collide on a
-- unique constraint and a family quoted a number with no shape to it.
-- ----------------------------------------------------------------------------
ALTER TABLE number_series DROP CONSTRAINT IF EXISTS number_series_kind_check;
ALTER TABLE number_series ADD CONSTRAINT number_series_kind_check
    CHECK (kind IN ('admission','roll','invoice','receipt','certificate','application'));

INSERT INTO number_series (school_id, kind, scope_id, pattern, next_value, reset_policy)
SELECT s.id, 'application', NULL, 'APP{YY}{SEQ:4}',
       -- Start past what the school already holds, so a generated number cannot
       -- collide with one that was typed in by hand (V019 does the same).
       COALESCE((SELECT count(*) FROM admission_application a WHERE a.school_id = s.id), 0) + 1,
       'yearly'
FROM school s
ON CONFLICT DO NOTHING;

-- ----------------------------------------------------------------------------
-- Configuring the funnel is a setup action, not a step in working it: the
-- counsellor who moves applications all day does not get to decide whether the
-- school runs an entrance test.
-- ----------------------------------------------------------------------------
INSERT INTO role_perm (role_code, perm_code)
SELECT r.code, 'admission.policy.manage'
FROM (VALUES ('principal'), ('vice_principal'), ('it_admin'), ('registrar')) AS r(code)
ON CONFLICT DO NOTHING;

-- RLS for admission_policy, which carries school_id (V009's rule). Same block
-- as V021/V022/V025: it covers any table with the column and no policy yet.
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
