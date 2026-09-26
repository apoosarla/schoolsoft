-- ----------------------------------------------------------------------------
-- Which Saturdays a school teaches on, when the answer is not a rule.
--
-- V016 offered four answers: all, none, odd, even. Plenty of schools do not
-- fit any of them — one Saturday a month, the 2nd and 4th, the 1st and 3rd —
-- and those schools had to either declare a `working_saturday` calendar entry
-- every month forever, or accept a wrong denominator under every attendance
-- percentage.
--
-- `saturday_rule = 'nth'` says "the set, not a rule", and `saturday_weeks`
-- holds it as a five-character mask over the 1st..5th Saturday of the calendar
-- month, '1' = taught. The mask shape is deliberately the same idea as
-- `weekday_mask` above it. The existing four rules keep working and keep
-- meaning what they meant, so no row needs rewriting.
-- ----------------------------------------------------------------------------

ALTER TABLE working_day_pattern
    ADD COLUMN IF NOT EXISTS saturday_weeks TEXT;

ALTER TABLE working_day_pattern
    DROP CONSTRAINT IF EXISTS working_day_pattern_saturday_rule_check;

ALTER TABLE working_day_pattern
    ADD CONSTRAINT working_day_pattern_saturday_rule_check
    CHECK (saturday_rule IN ('all', 'none', 'odd', 'even', 'nth'));

-- The mask is required by 'nth' and meaningless without it: a pattern that
-- says "these Saturdays" and does not say which is not a pattern, and one that
-- carries a set it does not use would read as though the set were in force.
ALTER TABLE working_day_pattern
    DROP CONSTRAINT IF EXISTS working_day_pattern_saturday_weeks_chk;

ALTER TABLE working_day_pattern
    ADD CONSTRAINT working_day_pattern_saturday_weeks_chk
    CHECK (
        (saturday_rule = 'nth' AND saturday_weeks ~ '^[01]{5}$')
        OR (saturday_rule <> 'nth' AND saturday_weeks IS NULL)
    );
