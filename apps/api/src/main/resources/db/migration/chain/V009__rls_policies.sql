-- ============================================================================
-- Row-Level Security (per §5a defence-in-depth).
-- Schema-per-chain already isolates between chains; RLS protects cross-school
-- inside one chain. Enforced via app.school_id GUC set by tenant resolver.
-- ============================================================================

-- Helper: returns the current request's school_id from the session GUC.
-- Returns NULL when the GUC is unset (e.g. migration / job context).
CREATE OR REPLACE FUNCTION current_school_id() RETURNS UUID AS $$
DECLARE
  v TEXT;
BEGIN
  v := current_setting('app.school_id', true);
  IF v IS NULL OR v = '' THEN
    RETURN NULL;
  END IF;
  RETURN v::UUID;
EXCEPTION WHEN others THEN
  RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

-- Helper: tells whether the session is in "trusted" mode (bypass RLS).
-- Used by jobs / cross-school aggregation queries that still belong to a chain.
CREATE OR REPLACE FUNCTION is_trusted_session() RETURNS BOOLEAN AS $$
BEGIN
  RETURN coalesce(current_setting('app.trusted', true), 'false') = 'true';
END;
$$ LANGUAGE plpgsql STABLE;

-- Apply RLS to all school-scoped tables.
DO $$
DECLARE
  t TEXT;
BEGIN
  FOR t IN
    SELECT table_name FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND column_name = 'school_id'
      AND table_name NOT IN ('school')   -- school is the anchor; no policy needed
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY %I_school_isolation ON %I
         USING (is_trusted_session() OR school_id = current_school_id() OR current_school_id() IS NULL)
         WITH CHECK (is_trusted_session() OR school_id = current_school_id() OR current_school_id() IS NULL)',
      t, t
    );
  END LOOP;
END $$;

-- school table itself: scope by id when GUC is set; otherwise visible (chain admin).
ALTER TABLE school ENABLE ROW LEVEL SECURITY;
ALTER TABLE school FORCE ROW LEVEL SECURITY;
CREATE POLICY school_self_isolation ON school
  USING (is_trusted_session() OR current_school_id() IS NULL OR id = current_school_id())
  WITH CHECK (is_trusted_session() OR current_school_id() IS NULL OR id = current_school_id());
