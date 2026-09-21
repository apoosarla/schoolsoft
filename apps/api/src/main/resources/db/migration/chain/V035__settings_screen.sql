-- ----------------------------------------------------------------------------
-- A school-settings screen, and the entrance test moves onto it.
--
-- The entrance-test switch shipped on the admissions screen, beside the
-- pipeline it reshapes. That put a school-wide setting inside a screen people
-- work in all day: whether the school holds an entrance test is not a step in
-- working the funnel, it is a fact about the school, and it belongs with the
-- other facts about the school rather than above a list of applicants.
--
-- The permission has not changed -- `admission.policy.manage`, added in V034.
-- This only says who sees the screen. The two lists are deliberately the same:
-- a screen whose only control refuses everyone who can reach it is a dead end.
-- ----------------------------------------------------------------------------
UPDATE role
   SET screen_keys = array_append(screen_keys, 'settings'),
       -- `role` is versioned (V028) and admin-web sends the version back on
       -- save, so a migration that edits a role behind the UI has to move it
       -- too, or the next save from a stale screen wins a conflict it should
       -- have lost.
       version = version + 1
 WHERE code IN ('principal', 'vice_principal', 'it_admin', 'registrar')
   AND NOT ('settings' = ANY (screen_keys));
