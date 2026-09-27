-- ============================================================================
-- BUG-18. Linking a driver to a staff record used to grant nothing, so a
-- linked driver could not open the driver app. DriverService now grants the
-- `driver` role with the link; this gives it to the links made before that.
--
-- Only to staff who hold no other role. A driver linked to somebody who
-- already runs the office (the principal, in the dev data that exposed this)
-- is a wrong link, not a missing grant — widening that person's access here
-- would make the mistake worse. Those links are left for a person to correct.
-- ============================================================================

INSERT INTO staff_role (id, staff_id, role_code, scope_type, scope_id)
SELECT gen_random_uuid(), d.staff_id, 'driver', 'school', d.school_id
FROM driver d
WHERE d.staff_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM staff_role r
    WHERE r.staff_id = d.staff_id AND r.revoked_at IS NULL
  )
ON CONFLICT (staff_id, role_code, scope_type, scope_id) DO UPDATE SET revoked_at = NULL;
