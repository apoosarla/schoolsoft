-- ----------------------------------------------------------------------------
-- A billing cycle covers a period (ADM-16).
--
-- Until now a run was a label and a due date: "Term 2", due on the 10th. That
-- is enough to bill a child who was there all along and says nothing about one
-- who joined in October — generation billed the full structure amount to
-- whoever was on the register on the day it ran.
--
-- With the months the cycle covers on the run, a mid-year joiner's recurring
-- heads are shared out by whole months from the joining month (see
-- fees/internal/ProRata). Both columns are nullable: every run made before
-- this, and any run the office makes without naming a period, bills whole, so
-- this changes no school's invoices on the day it lands.
-- ----------------------------------------------------------------------------

ALTER TABLE fee_schedule_run
    ADD COLUMN IF NOT EXISTS period_start DATE,
    ADD COLUMN IF NOT EXISTS period_end   DATE;

ALTER TABLE fee_schedule_run
    DROP CONSTRAINT IF EXISTS fee_schedule_run_period_is_whole;
ALTER TABLE fee_schedule_run
    ADD CONSTRAINT fee_schedule_run_period_is_whole
        CHECK ((period_start IS NULL AND period_end IS NULL)
            OR (period_start IS NOT NULL AND period_end IS NOT NULL AND period_end >= period_start));
