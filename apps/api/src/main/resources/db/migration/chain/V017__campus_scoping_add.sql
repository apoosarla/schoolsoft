-- ============================================================================
-- Phase 1 — GAP-24, step 1 of 3: add campus_id as nullable and backfill it.
--
-- Split across three migrations on purpose (remediation plan, risk table): on a
-- populated chain the backfill is the slow part, and an operator needs to be
-- able to run it apart from the DDL and re-run it if it is interrupted. This
-- file is safe to re-run; V018 flips the columns to NOT NULL once it reports
-- zero remaining nulls.
-- ============================================================================

ALTER TABLE section        ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campus(id);
ALTER TABLE staff          ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campus(id);
ALTER TABLE timetable_slot ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campus(id);
-- A biometric reader hangs on one gate of one campus; without this the device
-- registry cannot say which campus's attendance it is entitled to write.
ALTER TABLE device         ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campus(id);

-- Every school gets a primary campus, so a school that was set up before campus
-- meant anything still has somewhere for its rows to land.
INSERT INTO campus (school_id, name, is_primary)
SELECT s.id, 'Main Campus', TRUE
FROM school s
WHERE NOT EXISTS (SELECT 1 FROM campus c WHERE c.school_id = s.id)
ON CONFLICT (school_id, name) DO NOTHING;

-- A school with campuses but none marked primary: promote the oldest.
UPDATE campus c SET is_primary = TRUE
WHERE NOT EXISTS (
        SELECT 1 FROM campus p WHERE p.school_id = c.school_id AND p.is_primary
      )
  AND c.id = (
        SELECT id FROM campus x WHERE x.school_id = c.school_id ORDER BY name LIMIT 1
      );

UPDATE section s SET campus_id = (
    SELECT c.id FROM campus c WHERE c.school_id = s.school_id AND c.is_primary LIMIT 1
) WHERE s.campus_id IS NULL;

UPDATE staff st SET campus_id = (
    SELECT c.id FROM campus c WHERE c.school_id = st.school_id AND c.is_primary LIMIT 1
) WHERE st.campus_id IS NULL;

UPDATE device d SET campus_id = (
    SELECT c.id FROM campus c WHERE c.school_id = d.school_id AND c.is_primary LIMIT 1
) WHERE d.campus_id IS NULL;

-- timetable_slot has no school_id of its own; it inherits the campus of the
-- section whose week it belongs to.
UPDATE timetable_slot t SET campus_id = (
    SELECT s.campus_id FROM section s WHERE s.id = t.section_id
) WHERE t.campus_id IS NULL;

CREATE INDEX IF NOT EXISTS section_campus_idx        ON section (campus_id);
CREATE INDEX IF NOT EXISTS staff_campus_idx          ON staff (campus_id);
CREATE INDEX IF NOT EXISTS timetable_slot_campus_idx ON timetable_slot (campus_id);
CREATE INDEX IF NOT EXISTS device_campus_idx         ON device (campus_id);
