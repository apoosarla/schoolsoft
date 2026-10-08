-- ============================================================================
-- A timetable slot's weekday is ISO: 1 is Monday, 7 is Sunday. That is what
-- the API accepts, what java.time.DayOfWeek hands every day read, and what
-- every screen has sent since the Sunday-as-0 form was fixed. V004's check
-- still said 0..6, so the one day the API allows and the column refused was
-- Sunday — a school that teaches on it got a constraint violation instead of
-- a slot — and a 0 written before the fix sat on no weekday at all, matched
-- by no day view and counted by no clash check.
-- ============================================================================

-- The old check goes first: it is what refuses the 7 the stray rows move to.
ALTER TABLE timetable_slot
    DROP CONSTRAINT IF EXISTS timetable_slot_day_of_week_check;

UPDATE timetable_slot SET day_of_week = 7 WHERE day_of_week = 0;

ALTER TABLE timetable_slot
    ADD CONSTRAINT timetable_slot_day_of_week_check CHECK (day_of_week BETWEEN 1 AND 7);
