-- ============================================================================
-- Transport, Library, Admissions. Per §7 Layer 4 + §13.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Transport (per §11 hardware plane + §15 driver app)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vehicle (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    registration_no TEXT NOT NULL,
    model           TEXT,
    capacity        INT,
    rc_expires_on   DATE,
    insurance_expires_on DATE,
    fitness_expires_on DATE,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (school_id, registration_no)
);

CREATE TABLE IF NOT EXISTS driver (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    staff_id        UUID REFERENCES staff(id),          -- optional: drivers often have staff records
    name            TEXT NOT NULL,
    phone           TEXT,
    license_no      TEXT,
    license_expires_on DATE,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS transport_route (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    code            TEXT NOT NULL,
    name            TEXT NOT NULL,
    direction       TEXT NOT NULL DEFAULT 'pickup' CHECK (direction IN ('pickup','drop','both')),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (school_id, code)
);

CREATE TABLE IF NOT EXISTS transport_stop (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    route_id        UUID NOT NULL REFERENCES transport_route(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    sort_order      INT NOT NULL DEFAULT 0,
    lat             NUMERIC(10,7),
    lng             NUMERIC(10,7),
    pickup_time     TIME,
    drop_time       TIME,
    geofence_radius_m INT NOT NULL DEFAULT 100,
    fee             NUMERIC(10,2)
);

CREATE TABLE IF NOT EXISTS route_assignment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id        UUID NOT NULL REFERENCES transport_route(id) ON DELETE CASCADE,
    vehicle_id      UUID NOT NULL REFERENCES vehicle(id),
    driver_id       UUID NOT NULL REFERENCES driver(id),
    effective_from  DATE NOT NULL,
    effective_to    DATE
);

CREATE TABLE IF NOT EXISTS student_transport (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    route_id        UUID NOT NULL REFERENCES transport_route(id),
    stop_id         UUID NOT NULL REFERENCES transport_stop(id),
    starts_on       DATE NOT NULL,
    ends_on         DATE,
    UNIQUE (student_id, route_id, starts_on)
);

-- GPS pings — partitioned by month at the app layer / TimescaleDB hypertable
-- in prod. For dev we keep it as a regular table; add Timescale extension when
-- volume warrants.
CREATE TABLE IF NOT EXISTS gps_ping (
    id              BIGSERIAL,
    vehicle_id      UUID NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    occurred_at     TIMESTAMPTZ NOT NULL,
    lat             NUMERIC(10,7) NOT NULL,
    lng             NUMERIC(10,7) NOT NULL,
    speed_kmh       NUMERIC(6,2),
    heading         NUMERIC(6,2),
    ignition        BOOLEAN,
    raw_payload     JSONB,
    PRIMARY KEY (vehicle_id, occurred_at, id)
);
CREATE INDEX IF NOT EXISTS gps_vehicle_time_idx ON gps_ping(vehicle_id, occurred_at DESC);

-- Trip lifecycle (driver-initiated start/end → live tracking trigger).
CREATE TABLE IF NOT EXISTS trip (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    route_id        UUID NOT NULL REFERENCES transport_route(id),
    vehicle_id      UUID NOT NULL REFERENCES vehicle(id),
    driver_id       UUID NOT NULL REFERENCES driver(id),
    direction       TEXT NOT NULL CHECK (direction IN ('pickup','drop')),
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    manifest        JSONB                              -- student ids on trip
);

-- ----------------------------------------------------------------------------
-- Library
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS library_title (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    isbn            TEXT,
    title           TEXT NOT NULL,
    author          TEXT,
    publisher       TEXT,
    year            INT,
    subject_tags    TEXT[],
    cover_file_id   UUID
);

CREATE TABLE IF NOT EXISTS library_copy (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title_id        UUID NOT NULL REFERENCES library_title(id) ON DELETE CASCADE,
    barcode         TEXT NOT NULL UNIQUE,
    status          TEXT NOT NULL DEFAULT 'available'
                      CHECK (status IN ('available','issued','reserved','lost','damaged','withdrawn'))
);

CREATE TABLE IF NOT EXISTS library_issue (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    copy_id         UUID NOT NULL REFERENCES library_copy(id),
    member_type     TEXT NOT NULL CHECK (member_type IN ('student','staff')),
    member_id       UUID NOT NULL,
    issued_on       DATE NOT NULL DEFAULT CURRENT_DATE,
    due_on          DATE NOT NULL,
    returned_on     DATE,
    fine            NUMERIC(10,2) NOT NULL DEFAULT 0,
    fine_paid       BOOLEAN NOT NULL DEFAULT FALSE
);

-- ----------------------------------------------------------------------------
-- Admissions funnel (per §13)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admission_application (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    academic_year_id UUID NOT NULL REFERENCES academic_year(id),
    grade_id        UUID NOT NULL REFERENCES grade(id),
    application_no  TEXT NOT NULL,
    -- Applicant identity (pre-student; student record created on seat confirm)
    applicant_first_name TEXT NOT NULL,
    applicant_last_name  TEXT,
    applicant_dob   DATE,
    applicant_gender TEXT,
    guardian_name   TEXT NOT NULL,
    guardian_phone  TEXT NOT NULL,
    guardian_email  TEXT,
    source          TEXT,                              -- 'website' | 'walkin' | 'referral' | 'ad'
    -- State machine state per §13
    state           TEXT NOT NULL DEFAULT 'lead'
                      CHECK (state IN ('lead','application_started','document_pending','fee_pending','review','test_scheduled','test_done','offered','accepted','waitlist','rejected','enrolled','lapsed')),
    documents       JSONB NOT NULL DEFAULT '[]'::jsonb,
    test_score      NUMERIC(6,2),
    interview_notes TEXT,
    offer_expires_on DATE,
    converted_student_id UUID REFERENCES student(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (school_id, application_no)
);
CREATE INDEX IF NOT EXISTS admission_state_idx ON admission_application(school_id, state);

CREATE TABLE IF NOT EXISTS admission_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id  UUID NOT NULL REFERENCES admission_application(id) ON DELETE CASCADE,
    event_type      TEXT NOT NULL,                     -- 'state_change' | 'fee_paid' | 'doc_uploaded' | 'test_scheduled'
    from_state      TEXT,
    to_state        TEXT,
    actor_user_id   UUID,
    payload         JSONB,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
