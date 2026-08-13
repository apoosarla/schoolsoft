-- Grants the two admin-web screens added for Phases 1 and 5.
--
-- `calendar` is the school calendar, working-day pattern, academic-year
-- lifecycle and same-day closure declaration: office roles, not teaching ones,
-- because declaring a closure voids attendance and messages every guardian.
UPDATE role
SET screen_keys = array_append(screen_keys, 'calendar')
WHERE code IN ('principal', 'vice_principal', 'it_admin', 'academic_coordinator', 'registrar')
  AND NOT ('calendar' = ANY(screen_keys));

-- `exams` is the exam timetable, clash check, publication and hall tickets.
-- The exams officer is the role the screen exists for; the heads and the
-- academic coordinator hold it because they are who unblocks a clash.
UPDATE role
SET screen_keys = array_append(screen_keys, 'exams')
WHERE code IN ('principal', 'vice_principal', 'it_admin', 'academic_coordinator', 'exams_officer')
  AND NOT ('exams' = ANY(screen_keys));
