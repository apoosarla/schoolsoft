-- ----------------------------------------------------------------------------
-- The setup checklist gets a screen.
--
-- V036 made "can this school open?" a question the API answers; this is who
-- sees the answer in school-web. The list is the three roles V036 gave
-- `school.onboard`, and not the four that hold `settings`: the registrar can
-- read the checklist through the API, but a screen whose only actions refuse
-- them is a dead end, and the checklist's actions are all `school.onboard`.
--
-- The chain admin is not here for a different reason: screen keys are role
-- grants, and a chain admin holds no role — they sign in to the same
-- school-web as the office, but their navigation comes from their subject
-- type rather than from this table.
-- ----------------------------------------------------------------------------
UPDATE role
   SET screen_keys = array_append(screen_keys, 'setup'),
       -- `role` is versioned (V028) and school-web sends the version back on
       -- save, so a migration that edits a role behind the UI has to move it
       -- too, or the next save from a stale screen wins a conflict it should
       -- have lost.
       version = version + 1
 WHERE code IN ('principal', 'vice_principal', 'it_admin')
   AND NOT ('setup' = ANY (screen_keys));
