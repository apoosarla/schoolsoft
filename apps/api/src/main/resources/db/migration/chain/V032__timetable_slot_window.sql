-- ----------------------------------------------------------------------------
-- V032 — a timetable slot's effective window is a window (TT-05).
--
-- Slots have carried effective_from/effective_to since V004, but nothing
-- stopped a window that closes before it opens, and the week reads ignored the
-- window entirely — so a mid-year revision rewrote history instead of
-- superseding it from a date. The reads now filter on the window and
-- POST /v1/timetable/slots/{id}/retire closes one; this is the invariant they
-- both rely on.
--
-- No RLS block: timetable_slot has no school_id, it borrows section's (V018
-- pins its campus to the section's, and every read joins the section or is
-- keyed by one).
-- ----------------------------------------------------------------------------

ALTER TABLE timetable_slot
    DROP CONSTRAINT IF EXISTS timetable_slot_window_chk;
ALTER TABLE timetable_slot
    ADD CONSTRAINT timetable_slot_window_chk
    CHECK (effective_to IS NULL OR effective_to >= effective_from);

-- The week reads are (section | teacher, day_of_week) narrowed by the window;
-- the existing tt_*_dow_idx indexes stop short of the dates.
CREATE INDEX IF NOT EXISTS tt_section_window_idx
    ON timetable_slot (section_id, effective_from, effective_to);
CREATE INDEX IF NOT EXISTS tt_teacher_window_idx
    ON timetable_slot (teacher_staff_id, effective_from, effective_to);
