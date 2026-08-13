-- Grants the admin-web screen added for Phase 2.
--
-- `academics` is the structural master: the subject catalogue, what each
-- section is taught and by whom, the option blocks a grade offers, section
-- capacity and roll numbers, and the bell schedules the day's periods run on.
--
-- The academic coordinator is the role the screen exists for. The registrar
-- holds it because roll numbers and seats are student-record work, and the
-- heads and IT admin because they are who unblocks a full section or a wrong
-- option block. It is deliberately not a teaching role: changing what a
-- section is taught re-prices every timetable, mark sheet and report card
-- hanging off it.
UPDATE role
SET screen_keys = array_append(screen_keys, 'academics')
WHERE code IN ('principal', 'vice_principal', 'it_admin', 'academic_coordinator', 'registrar')
  AND NOT ('academics' = ANY(screen_keys));
