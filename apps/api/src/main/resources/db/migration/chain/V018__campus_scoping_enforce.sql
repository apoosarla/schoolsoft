-- ============================================================================
-- Phase 1 — GAP-24, step 3 of 3: campus_id becomes mandatory.
--
-- Separate from the backfill (V017) so an operator can verify
--   SELECT count(*) FROM section WHERE campus_id IS NULL;
-- on a populated chain before taking the ACCESS EXCLUSIVE lock this needs.
-- Postgres validates the constraint against existing rows here, which is why
-- the backfill must be complete first.
-- ============================================================================

-- A section and its campus must belong to the same school. Postgres cannot
-- express that as a plain FK (it spans two columns on two tables), so it is a
-- trigger — cheap, and it catches the mistake that actually happens: an admin
-- picking a campus from another school's list in a chain-wide UI.
--
-- The same trigger also fills the column in when the caller omits it. Most
-- schools have exactly one campus and will never name it, so requiring every
-- writer to look up the primary campus first would put the same three-line
-- query in every insert path in the codebase — and the one that forgot would
-- fail at runtime instead of defaulting sensibly.
CREATE OR REPLACE FUNCTION campus_belongs_to_same_school() RETURNS TRIGGER AS $$
DECLARE
  campus_school UUID;
BEGIN
  IF NEW.campus_id IS NULL THEN
    SELECT id INTO NEW.campus_id FROM campus
      WHERE school_id = NEW.school_id
      ORDER BY is_primary DESC, name
      LIMIT 1;
    IF NEW.campus_id IS NULL THEN
      RAISE EXCEPTION 'School % has no campus to default to', NEW.school_id
        USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
  END IF;

  SELECT school_id INTO campus_school FROM campus WHERE id = NEW.campus_id;
  IF campus_school IS DISTINCT FROM NEW.school_id THEN
    RAISE EXCEPTION 'Campus % belongs to school %, not %',
      NEW.campus_id, campus_school, NEW.school_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS section_campus_same_school_trg ON section;
CREATE TRIGGER section_campus_same_school_trg
    BEFORE INSERT OR UPDATE OF campus_id, school_id ON section
    FOR EACH ROW EXECUTE FUNCTION campus_belongs_to_same_school();

DROP TRIGGER IF EXISTS staff_campus_same_school_trg ON staff;
CREATE TRIGGER staff_campus_same_school_trg
    BEFORE INSERT OR UPDATE OF campus_id, school_id ON staff
    FOR EACH ROW EXECUTE FUNCTION campus_belongs_to_same_school();

DROP TRIGGER IF EXISTS device_campus_same_school_trg ON device;
CREATE TRIGGER device_campus_same_school_trg
    BEFORE INSERT OR UPDATE OF campus_id, school_id ON device
    FOR EACH ROW EXECUTE FUNCTION campus_belongs_to_same_school();

-- timetable_slot has no school_id of its own: a slot belongs to the campus its
-- section sits on, and may not be moved off it.
CREATE OR REPLACE FUNCTION slot_campus_follows_section() RETURNS TRIGGER AS $$
DECLARE
  section_campus UUID;
BEGIN
  SELECT campus_id INTO section_campus FROM section WHERE id = NEW.section_id;
  IF NEW.campus_id IS NULL THEN
    NEW.campus_id := section_campus;
  ELSIF NEW.campus_id IS DISTINCT FROM section_campus THEN
    RAISE EXCEPTION 'Timetable slot campus % is not the campus of section % (%)',
      NEW.campus_id, NEW.section_id, section_campus
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS timetable_slot_campus_trg ON timetable_slot;
CREATE TRIGGER timetable_slot_campus_trg
    BEFORE INSERT OR UPDATE OF campus_id, section_id ON timetable_slot
    FOR EACH ROW EXECUTE FUNCTION slot_campus_follows_section();

-- NOT NULL last: the triggers above are what make it satisfiable for a caller
-- that does not name a campus.
ALTER TABLE section        ALTER COLUMN campus_id SET NOT NULL;
ALTER TABLE staff          ALTER COLUMN campus_id SET NOT NULL;
ALTER TABLE timetable_slot ALTER COLUMN campus_id SET NOT NULL;
ALTER TABLE device         ALTER COLUMN campus_id SET NOT NULL;
