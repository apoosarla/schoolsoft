-- ----------------------------------------------------------------------------
-- The admission fee, collected at `fee_pending` (ADM-13).
--
-- The funnel has had a `fee_pending` stage since V007 and nothing to collect in
-- it: an invoice named a student or a family, and an applicant is neither until
-- the seat is confirmed. So the office took the money outside the system and
-- moved the application on by hand, and the ledger never saw it.
--
-- 1. admission_fee — what a school charges to process an application, per
--    grade per year, because a nursery intake and a Class 11 intake are priced
--    differently. No row means no fee, and no gate on the funnel.
--
-- 2. fee_invoice.admission_application_id — a third kind of payer. The invoice
--    is raised against the application, and conversion writes the new student
--    onto it, so the fee a family paid in March is on the child's account in
--    April rather than in a parallel record.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS admission_fee (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id        UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    academic_year_id UUID NOT NULL REFERENCES academic_year(id) ON DELETE CASCADE,
    grade_id         UUID NOT NULL REFERENCES grade(id) ON DELETE CASCADE,
    amount           NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (school_id, academic_year_id, grade_id)
    -- No `version`: one number, set occasionally by one person, which is the
    -- case V028 gives for leaving a table unversioned.
);

-- A table with a school_id needs its own policy; V009's loop ran long before
-- this table existed.
ALTER TABLE admission_fee ENABLE ROW LEVEL SECURITY;
ALTER TABLE admission_fee FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS admission_fee_school_isolation ON admission_fee;
CREATE POLICY admission_fee_school_isolation ON admission_fee
    USING (is_trusted_session() OR school_id = current_school_id() OR current_school_id() IS NULL)
    WITH CHECK (is_trusted_session() OR school_id = current_school_id() OR current_school_id() IS NULL);

-- No ON DELETE action: a bill does not lose its payer because somebody tidied
-- the funnel.
ALTER TABLE fee_invoice
    ADD COLUMN IF NOT EXISTS admission_application_id UUID REFERENCES admission_application(id);

ALTER TABLE fee_invoice
    DROP CONSTRAINT IF EXISTS fee_invoice_has_a_payer;
ALTER TABLE fee_invoice
    ADD CONSTRAINT fee_invoice_has_a_payer
        CHECK (student_id IS NOT NULL OR family_id IS NOT NULL OR admission_application_id IS NOT NULL);

-- One live admission invoice per application. An application that goes back to
-- `document_pending` and returns to `fee_pending` finds the bill it already
-- has; this is what makes that true under two simultaneous moves as well.
CREATE UNIQUE INDEX IF NOT EXISTS fee_invoice_one_per_application
    ON fee_invoice (admission_application_id)
    WHERE admission_application_id IS NOT NULL AND status <> 'cancelled';
