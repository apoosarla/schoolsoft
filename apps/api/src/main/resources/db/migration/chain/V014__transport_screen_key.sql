-- Grants the new admin-web Transport screen (vehicles, drivers, routes,
-- student assignment, live trips) to the same top-level roles that already
-- hold full operational access (see V013 principal/vice_principal/it_admin).
UPDATE role
SET screen_keys = array_append(screen_keys, 'transport')
WHERE code IN ('principal', 'vice_principal', 'it_admin')
  AND NOT ('transport' = ANY(screen_keys));
