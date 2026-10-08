-- ============================================================================
-- Staff lifecycle (GAP-44, and the staff-exit half of GAP-27).
--
-- A school could not put a teacher on its books without SQL: the people module
-- read staff and never wrote one, apart from the first keyholder the chain's HQ
-- hands a new school to. And nobody could leave — `left_on` has been on the
-- table since V002 with nothing writing it and nothing reading it.
--
-- Four things:
--
--   staff.version          the record is edited as a form, so it is versioned
--   staff.left_on          becomes the authority on "still on the books"
--   section_subject_teacher.effective_from / effective_to
--                          so a handover has a date instead of a rewrite
--   staff.manage           the permission, and the screen that uses it
-- ============================================================================

-- ----------------------------------------------------------------------------
-- The staff record is written back whole from a form (V028 explains the rule).
-- ----------------------------------------------------------------------------
ALTER TABLE staff ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;

-- ----------------------------------------------------------------------------
-- "Is this person on the books?" is a question about a date, for the reason
-- "is this child at the school?" is (V031): an exit filed on the 1st for a last
-- working day of the 30th has to leave the person able to sign in and mark
-- their register until the 30th, and shut the door on the 1st. `is_active`
-- cannot say that. Sign-in, token refresh and permission resolution all read
-- `left_on` against the school's today — see `iam/api/StaffTenure.java`, the
-- single copy of the predicate — so there is no job whose failure would leave
-- a leaver holding the keys.
--
-- Role grants are deliberately not revoked by an exit. The grant is the record
-- of what the person held while they worked here; the date is what stops it
-- counting.
-- ----------------------------------------------------------------------------
ALTER TABLE staff ADD COLUMN IF NOT EXISTS exit_reason TEXT;
ALTER TABLE staff ADD COLUMN IF NOT EXISTS exit_recorded_at TIMESTAMPTZ;
-- Who took over their sections and periods, when there were any to take over.
ALTER TABLE staff ADD COLUMN IF NOT EXISTS successor_staff_id UUID REFERENCES staff(id);

ALTER TABLE staff DROP CONSTRAINT IF EXISTS staff_left_after_joining;
ALTER TABLE staff ADD CONSTRAINT staff_left_after_joining
    CHECK (left_on IS NULL OR joined_on IS NULL OR left_on >= joined_on);

COMMENT ON COLUMN staff.left_on IS
    'Last working day, inclusive. NULL means still on the books. Access and '
    'duties read this, not is_active.';

-- ----------------------------------------------------------------------------
-- A standing assignment gets a window, the way a timetable slot has one (V032).
--
-- Without it a handover is an UPDATE of `teacher_staff_id`, which takes the
-- section away from the leaver on the day the paperwork is filed rather than
-- the day they go. NULL on either side means open, so every existing row keeps
-- meaning what it meant.
--
-- The row is also how a section's compulsory subject set is derived
-- (`StudentSubjectRepository`), and that read ignores the window on purpose:
-- the subject does not stop being taught because its teacher changed.
-- ----------------------------------------------------------------------------
ALTER TABLE section_subject_teacher ADD COLUMN IF NOT EXISTS effective_from DATE;
ALTER TABLE section_subject_teacher ADD COLUMN IF NOT EXISTS effective_to DATE;

COMMENT ON COLUMN section_subject_teacher.effective_to IS
    'Last day this teacher holds the assignment, inclusive. NULL means open-ended.';

-- ----------------------------------------------------------------------------
-- Employee numbers come from the school's series like every other number.
-- ----------------------------------------------------------------------------
ALTER TABLE number_series DROP CONSTRAINT IF EXISTS number_series_kind_check;
ALTER TABLE number_series ADD CONSTRAINT number_series_kind_check
    CHECK (kind IN ('admission','roll','invoice','receipt','certificate','application','employee'));

INSERT INTO number_series (school_id, kind, scope_id, pattern, next_value, reset_policy)
SELECT s.id, 'employee', NULL, 'EMP{SEQ:4}',
       -- Start past what the school already holds, so a generated number cannot
       -- collide with one that was typed in by hand (V019 does the same).
       COALESCE((SELECT count(*) FROM staff st WHERE st.school_id = s.id), 0) + 1,
       'never'
FROM school s
ON CONFLICT DO NOTHING;

-- ----------------------------------------------------------------------------
-- Who may hire, edit and exit. The three roles that already hold `role.manage`:
-- putting somebody on the books is only useful to a person who can also say
-- what they do here.
-- ----------------------------------------------------------------------------
INSERT INTO role_perm (role_code, perm_code)
SELECT r.code, 'staff.manage'
FROM (VALUES ('principal'), ('vice_principal'), ('it_admin')) AS r(code)
ON CONFLICT DO NOTHING;

UPDATE role
   SET screen_keys = array_append(screen_keys, 'staff'),
       -- `role` is versioned (V028); see V037 for why a migration moves it too.
       version = version + 1
 WHERE code IN ('principal', 'vice_principal', 'it_admin')
   AND NOT ('staff' = ANY (screen_keys));
