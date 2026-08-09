-- Role catalog for screen-level RBAC. Grants are recorded in the existing
-- staff_role table (§5, unused until now) as (staff_id, role_code, scope_type,
-- scope_id) rows — this migration turns role_code from a free-text
-- convention into a real catalog entry with a name, description, and the
-- set of admin-web screens it unlocks, and adds custom-role support (any
-- school can define additional roles beyond the seeded set).
--
-- Deliberately decoupled from how the caller authenticated (OTP today,
-- school SSO later) — role_code is keyed off staff_id, and staff_id is
-- reached from a JWT via user_account.subject_id, so swapping the auth
-- front door never touches this table.
CREATE TABLE IF NOT EXISTS role (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            TEXT NOT NULL UNIQUE,
    name            TEXT NOT NULL,
    description     TEXT,
    screen_keys     TEXT[] NOT NULL DEFAULT '{}',
    is_system       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE staff_role
    ADD CONSTRAINT staff_role_role_code_fkey FOREIGN KEY (role_code) REFERENCES role(code);

-- Seeded personas for a Cambridge-curriculum international school in India.
-- 'admin' in screen_keys is the Roles & Users management screen itself.
INSERT INTO role (code, name, description, screen_keys, is_system) VALUES
('principal', 'Principal', 'Head of school — full oversight across academics, admissions, and operations.',
    ARRAY['dashboard','students','admissions','attendance','fees','timetable','assessment','lms','comms','library','admin'], TRUE),
('vice_principal', 'Vice Principal', 'Deputy head — full operational access mirroring the Principal.',
    ARRAY['dashboard','students','admissions','attendance','fees','timetable','assessment','lms','comms','library','admin'], TRUE),
('it_admin', 'IT Administrator', 'Manages system access, staff accounts, and roles.',
    ARRAY['dashboard','students','admissions','attendance','fees','timetable','assessment','lms','comms','library','admin'], TRUE),
('academic_coordinator', 'Cambridge Coordinator', 'Coordinates curriculum delivery and Cambridge programme compliance across sections.',
    ARRAY['dashboard','students','timetable','assessment','lms','comms'], TRUE),
('exams_officer', 'Exams Officer', 'Manages Cambridge assessment cycles, entries, and results.',
    ARRAY['dashboard','students','assessment','comms'], TRUE),
('registrar', 'Registrar', 'Runs the admissions funnel and student records.',
    ARRAY['dashboard','students','admissions','comms'], TRUE),
('class_teacher', 'Class Teacher', 'Form tutor responsible for a homeroom section''s attendance, progress, and communication.',
    ARRAY['dashboard','students','attendance','timetable','assessment','lms','comms'], TRUE),
('subject_teacher', 'Subject Teacher', 'Teaches one or more subjects across sections.',
    ARRAY['dashboard','timetable','assessment','lms','comms'], TRUE),
('accountant', 'Accountant', 'Manages fee invoicing, collections, and the ledger.',
    ARRAY['dashboard','fees'], TRUE),
('librarian', 'Librarian', 'Manages the library catalogue and circulation.',
    ARRAY['dashboard','library'], TRUE),
('front_office', 'Front Office', 'Handles walk-in enquiries, admissions support, and parent communication.',
    ARRAY['dashboard','students','admissions','comms'], TRUE)
ON CONFLICT (code) DO NOTHING;
