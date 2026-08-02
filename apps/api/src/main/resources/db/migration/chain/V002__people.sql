-- ============================================================================
-- People: student / guardian / staff. Per §5 + §16 (PII isolation).
-- ============================================================================

CREATE TABLE IF NOT EXISTS student (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    admission_no    TEXT NOT NULL,
    apaar_id        TEXT,                              -- nullable, populated post-onboarding
    first_name      TEXT NOT NULL,
    middle_name     TEXT,
    last_name       TEXT,
    dob             DATE,
    gender          TEXT CHECK (gender IN ('male','female','other','undisclosed')),
    blood_group     TEXT,
    photo_file_id   UUID,
    -- Sensitive PII goes in encrypted_payload; column-level encryption hook.
    -- Plain fields are non-sensitive only.
    encrypted_payload BYTEA,
    status          TEXT NOT NULL DEFAULT 'active'
                      CHECK (status IN ('applicant','active','withdrawn','transferred','graduated')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (school_id, admission_no)
);

CREATE TABLE IF NOT EXISTS guardian (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    first_name      TEXT NOT NULL,
    last_name       TEXT,
    phone           TEXT,
    email           TEXT,
    occupation      TEXT,
    encrypted_payload BYTEA,
    -- Channel opt-in flags (DPDP + WhatsApp policy). Stored here, source-of-truth
    -- per parent person, not per student.
    opt_in_whatsapp BOOLEAN NOT NULL DEFAULT FALSE,
    opt_in_email    BOOLEAN NOT NULL DEFAULT TRUE,
    opt_in_push     BOOLEAN NOT NULL DEFAULT TRUE,
    opt_in_sms      BOOLEAN NOT NULL DEFAULT FALSE,
    opt_in_source   TEXT,
    opt_in_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS guardian_student (
    guardian_id     UUID NOT NULL REFERENCES guardian(id) ON DELETE CASCADE,
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    relation        TEXT NOT NULL CHECK (relation IN ('father','mother','guardian','grandparent','other')),
    is_primary      BOOLEAN NOT NULL DEFAULT FALSE,
    is_custodial    BOOLEAN NOT NULL DEFAULT TRUE,
    is_payor        BOOLEAN NOT NULL DEFAULT FALSE,
    is_communications_recipient BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (guardian_id, student_id)
);
-- Only one primary guardian per student.
CREATE UNIQUE INDEX IF NOT EXISTS guardian_one_primary_per_student
    ON guardian_student (student_id) WHERE is_primary;

CREATE TABLE IF NOT EXISTS staff (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    employee_no     TEXT NOT NULL,
    first_name      TEXT NOT NULL,
    last_name       TEXT,
    email           TEXT,
    phone           TEXT,
    employment_type TEXT CHECK (employment_type IN ('permanent','contract','temp','visiting')),
    joined_on       DATE,
    left_on         DATE,
    encrypted_payload BYTEA,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (school_id, employee_no)
);

-- RBAC scope assignment (per §5 "RBAC scopes: action permitted on (scope_type, scope_id)").
CREATE TABLE IF NOT EXISTS staff_role (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id        UUID NOT NULL REFERENCES staff(id) ON DELETE CASCADE,
    role_code       TEXT NOT NULL,                     -- 'class_teacher' | 'subject_teacher' | 'hod' | 'principal' | 'admin' | 'counsellor'
    scope_type      TEXT NOT NULL,                     -- 'school' | 'campus' | 'section' | 'subject'
    scope_id        UUID NOT NULL,
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    UNIQUE (staff_id, role_code, scope_type, scope_id)
);

-- Now resolve forward-declared FK from section_subject_teacher.teacher_staff_id
ALTER TABLE section_subject_teacher
    ADD CONSTRAINT section_subject_teacher_teacher_fk
    FOREIGN KEY (teacher_staff_id) REFERENCES staff(id) ON DELETE CASCADE;

-- Enrolment is time-bounded participation of a student in a section.
CREATE TABLE IF NOT EXISTS enrolment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    section_id      UUID NOT NULL REFERENCES section(id) ON DELETE CASCADE,
    academic_year_id UUID NOT NULL REFERENCES academic_year(id),
    starts_on       DATE NOT NULL,
    ends_on         DATE,
    status          TEXT NOT NULL DEFAULT 'active'
                      CHECK (status IN ('active','withdrawn','transferred','graduated','promoted')),
    roll_no         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (student_id, section_id, academic_year_id)
);

-- A student may only have one active enrolment at a time.
CREATE UNIQUE INDEX IF NOT EXISTS enrolment_one_active_per_student
    ON enrolment (student_id) WHERE status = 'active';

CREATE INDEX IF NOT EXISTS enrolment_section_idx ON enrolment(section_id);

-- Auth account binding. A platform user (login identity) maps to staff or guardian
-- inside this chain. Identity is the *email or phone*, looked up in IAM.
CREATE TABLE IF NOT EXISTS user_account (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID,                              -- nullable for chain-scoped users (HQ admin)
    subject_type    TEXT NOT NULL CHECK (subject_type IN ('staff','guardian','student','chain_admin')),
    subject_id      UUID,                              -- staff.id | guardian.id | student.id
    email           TEXT,
    phone           TEXT,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (email),
    UNIQUE (phone),
    CHECK (email IS NOT NULL OR phone IS NOT NULL)
);
