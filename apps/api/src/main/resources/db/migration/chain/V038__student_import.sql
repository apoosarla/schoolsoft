-- ----------------------------------------------------------------------------
-- Bulk import of students and their families (GAP-23, ENR-09).
--
-- Schools arrive with a spreadsheet. Until now the only door was
-- `POST /v1/people/students`, one child at a time, which is why the gap was
-- filed as an onboarding blocker rather than a convenience.
--
-- The shape is preview-then-commit, and this table is what sits between them.
-- A preview parses the file, resolves every grade and section, checks every
-- row, and stores the result; the commit runs *that*, not the file again. So
-- what the office approved on screen is exactly what is written, and a
-- re-upload between the two cannot substitute a different file underneath the
-- approval.
--
-- `rows` holds the parsed batch: each row as it will be written, with the
-- errors found against it. jsonb rather than json because nothing here is
-- hashed and the order of keys is nobody's business -- unlike `certificate`,
-- where the bytes are the point.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS import_batch (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    kind            TEXT NOT NULL CHECK (kind IN ('student')),
    filename        TEXT,
    -- previewed: parsed and checked, nothing written.
    -- committed:  written, once. A batch commits at most once, which is what
    --             stops a double-click importing the same file twice.
    -- superseded: a later preview of the same file replaced it.
    status          TEXT NOT NULL DEFAULT 'previewed'
                      CHECK (status IN ('previewed', 'committed', 'superseded')),
    row_count       INT NOT NULL,
    error_count     INT NOT NULL,
    rows            JSONB NOT NULL,
    uploaded_by_staff_id UUID REFERENCES staff(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS import_batch_school_idx ON import_batch (school_id, created_at DESC);

-- A new table carrying school_id needs its own policy, or one school's
-- registrar can read another's file. Same block as V021/V022/V025/V036.
DO $$
DECLARE
    t TEXT;
BEGIN
    FOR t IN
        SELECT c.table_name FROM information_schema.columns c
        WHERE c.table_schema = current_schema()
          AND c.column_name = 'school_id'
          AND c.table_name = 'import_batch'
          AND NOT EXISTS (
              SELECT 1 FROM pg_policies p
              WHERE p.schemaname = current_schema() AND p.tablename = c.table_name)
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I_school_isolation ON %I
               USING (is_trusted_session() OR school_id = current_school_id() OR current_school_id() IS NULL)
               WITH CHECK (is_trusted_session() OR school_id = current_school_id() OR current_school_id() IS NULL)',
            t, t);
    END LOOP;
END $$;

-- ----------------------------------------------------------------------------
-- Who imports.
--
-- Its own permission rather than a reading of `student.manage` plus
-- `enrolment.manage`: the gate grammar has no "and", and an import that wrote
-- children but could not enrol them would leave the register half-built. One
-- code, one grant, and the endpoint says what it is.
--
-- The registrar is on this list and not on most structural ones -- running
-- admissions and student records is exactly whose job the spreadsheet is.
-- ----------------------------------------------------------------------------
INSERT INTO role_perm (role_code, perm_code)
SELECT r.code, 'student.import'
FROM (VALUES ('principal'), ('vice_principal'), ('it_admin'), ('registrar')) AS r(code)
ON CONFLICT DO NOTHING;
