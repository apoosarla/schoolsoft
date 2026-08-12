-- ============================================================================
-- Phase 3 — daily-operations correctness.
--
-- Three things happen every school day that the schema had no room for: a
-- teacher corrects a mark after the register has been signed off, an approved
-- leave has to stop being something a teacher re-types as attendance, and an
-- absent teacher's periods have to belong to somebody. All three are
-- amendments to a record of fact, so all three keep the prior value.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Marking window. Inside it a mark is a correction; outside it, an amendment.
--
-- The window is per school because the answer is administrative, not
-- technical: a school that signs the register off at the end of the day wants
-- hours, one that reviews weekly wants days.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS attendance_policy (
    school_id           UUID PRIMARY KEY REFERENCES school(id) ON DELETE CASCADE,
    edit_window_hours   INT NOT NULL DEFAULT 24 CHECK (edit_window_hours >= 0),
    -- Who may decide an amendment. A class teacher raises one; somebody above
    -- them signs it, which is the whole point of the workflow.
    approver_roles      TEXT[] NOT NULL
                          DEFAULT ARRAY['principal','vice_principal','academic_coordinator','it_admin'],
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- Amendments (ATT-06). The record keeps the current truth; the amendment keeps
-- what it used to be, who asked, who allowed it, and why.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS attendance_amendment (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id           UUID NOT NULL REFERENCES school(id),
    attendance_record_id UUID NOT NULL REFERENCES attendance_record(id) ON DELETE CASCADE,
    student_id          UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    section_id          UUID NOT NULL REFERENCES section(id),
    on_date             DATE NOT NULL,
    period_no           INT,
    old_status          TEXT NOT NULL,
    new_status          TEXT NOT NULL
                          CHECK (new_status IN ('present','absent','late','leave','excused','half_day')),
    reason              TEXT NOT NULL CHECK (length(btrim(reason)) > 0),
    requested_by_user_id UUID REFERENCES user_account(id),
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    status              TEXT NOT NULL DEFAULT 'pending'
                          CHECK (status IN ('pending','approved','rejected','withdrawn')),
    decided_by_user_id  UUID REFERENCES user_account(id),
    decided_at          TIMESTAMPTZ,
    decision_note       TEXT,
    CHECK (old_status <> new_status)
);

-- One open request per record: two pending amendments for the same register
-- line is two people arguing in the database.
CREATE UNIQUE INDEX IF NOT EXISTS attendance_amendment_one_pending
    ON attendance_amendment (attendance_record_id) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS attendance_amendment_school_status_idx
    ON attendance_amendment (school_id, status, requested_at DESC);

-- ----------------------------------------------------------------------------
-- Leave → attendance materialisation (ATT-05, ATT-13).
--
-- The link column is what makes the unwind exact: revoking an approval removes
-- the days that approval created and nothing else, so a teacher's manual mark
-- made in the same window survives.
-- ----------------------------------------------------------------------------
ALTER TABLE attendance_record
    ADD COLUMN IF NOT EXISTS leave_application_id UUID REFERENCES leave_application(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS attendance_leave_idx
    ON attendance_record (leave_application_id) WHERE leave_application_id IS NOT NULL;

ALTER TABLE staff_attendance
    ADD COLUMN IF NOT EXISTS leave_application_id UUID REFERENCES leave_application(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS staff_attendance_leave_idx
    ON staff_attendance (leave_application_id) WHERE leave_application_id IS NOT NULL;

ALTER TABLE leave_application
    ADD COLUMN IF NOT EXISTS materialised_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS materialised_days INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS decided_by_user_id UUID REFERENCES user_account(id);

-- Staff attendance carries a marked-at for the same reason attendance_record
-- does — a leave day materialised last night and one entered this morning are
-- different events.
ALTER TABLE staff_attendance
    ADD COLUMN IF NOT EXISTS marked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS notes TEXT;

-- ----------------------------------------------------------------------------
-- Cover (GAP-07, TT-08, STF-03). A cover is per slot per date: an absence is
-- a day, but a substitution is a period, and two periods of the same absence
-- can go to two different people.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS timetable_cover (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id           UUID NOT NULL REFERENCES school(id),
    slot_id             UUID NOT NULL REFERENCES timetable_slot(id) ON DELETE CASCADE,
    on_date             DATE NOT NULL,
    absent_staff_id     UUID NOT NULL REFERENCES staff(id),
    substitute_staff_id UUID NOT NULL REFERENCES staff(id),
    reason              TEXT,
    leave_application_id UUID REFERENCES leave_application(id) ON DELETE SET NULL,
    created_by_user_id  UUID REFERENCES user_account(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at        TIMESTAMPTZ,
    cancelled_by_user_id UUID REFERENCES user_account(id),
    CHECK (substitute_staff_id <> absent_staff_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS timetable_cover_one_per_slot_date
    ON timetable_cover (slot_id, on_date) WHERE cancelled_at IS NULL;
CREATE INDEX IF NOT EXISTS timetable_cover_substitute_idx
    ON timetable_cover (substitute_staff_id, on_date) WHERE cancelled_at IS NULL;
CREATE INDEX IF NOT EXISTS timetable_cover_school_date_idx
    ON timetable_cover (school_id, on_date);

-- ----------------------------------------------------------------------------
-- Audit (GAP-27). A high-risk mutation is not audited by recording that it
-- happened — it is audited by recording why, next to what the row looked like
-- before and after.
-- ----------------------------------------------------------------------------
ALTER TABLE audit_log
    ADD COLUMN IF NOT EXISTS reason TEXT,
    ADD COLUMN IF NOT EXISTS request_payload JSONB;

CREATE INDEX IF NOT EXISTS audit_action_time_idx ON audit_log(action, occurred_at DESC);

-- ----------------------------------------------------------------------------
-- RLS. V009 applied school isolation to every school-scoped table that existed
-- then, but it ran once: the tables added by Phases 1, 2 and 4 — and the three
-- above — have no policy. This re-runs V009's rule over anything school-scoped
-- that is still missing one, so a new table is protected by default rather than
-- by whoever remembers.
-- ----------------------------------------------------------------------------
DO $$
DECLARE t TEXT;
BEGIN
    FOR t IN
        SELECT c.table_name FROM information_schema.columns c
        WHERE c.table_schema = current_schema()
          AND c.column_name = 'school_id'
          AND c.table_name <> 'school'
          AND c.table_name IN (SELECT table_name FROM information_schema.tables
                               WHERE table_schema = current_schema() AND table_type = 'BASE TABLE')
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
