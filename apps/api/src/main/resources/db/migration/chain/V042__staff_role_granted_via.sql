-- ============================================================================
-- Where a role grant came from. Linking a driver to a staff record grants the
-- `driver` role and unlinking takes it back — but a `driver` grant made by
-- hand on the Roles screen looks the same, and unlinking used to take that
-- one too. `granted_via` names the grant a link made ('driver_link'); NULL is
-- a grant somebody made on purpose, and only the link's own is revoked by
-- the unlink.
--
-- A hand grant over an active link-made one claims it (NULL), so an office
-- that deliberately made somebody a driver keeps them one when the link ends.
-- ============================================================================

ALTER TABLE staff_role ADD COLUMN IF NOT EXISTS granted_via TEXT;

-- The grants V041 made: `driver`, on a linked staff record, holding nothing
-- else. Those are the link's by construction.
UPDATE staff_role r SET granted_via = 'driver_link'
WHERE r.role_code = 'driver' AND r.revoked_at IS NULL AND r.granted_via IS NULL
  AND EXISTS (SELECT 1 FROM driver d WHERE d.staff_id = r.staff_id)
  AND NOT EXISTS (
    SELECT 1 FROM staff_role o
    WHERE o.staff_id = r.staff_id AND o.revoked_at IS NULL AND o.role_code <> 'driver'
  );
