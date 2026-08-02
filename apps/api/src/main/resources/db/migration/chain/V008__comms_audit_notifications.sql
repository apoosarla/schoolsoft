-- ============================================================================
-- Comms (announcements, messaging), Notification delivery, Audit, Features,
-- Theming, Files, Outbox. Per §7 Layers 0 & 2.
-- ============================================================================

-- Files (object-store metadata). Actual bytes live in S3/MinIO.
CREATE TABLE IF NOT EXISTS file_object (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID REFERENCES school(id),
    bucket          TEXT NOT NULL,
    object_key      TEXT NOT NULL,
    mime_type       TEXT,
    size_bytes      BIGINT,
    checksum_sha256 TEXT,
    -- Image variants: { thumb: {key,w,h}, md: {...}, lg: {...} }
    variants        JSONB NOT NULL DEFAULT '{}'::jsonb,
    virus_scan_status TEXT NOT NULL DEFAULT 'pending'
                      CHECK (virus_scan_status IN ('pending','clean','infected','error','skipped')),
    retention_class TEXT NOT NULL DEFAULT 'default',
    uploaded_by_user_id UUID,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (bucket, object_key)
);
CREATE INDEX IF NOT EXISTS file_school_idx ON file_object(school_id);

-- Notification delivery receipts (per §10). Templates live in platform.whatsapp_template.
CREATE TABLE IF NOT EXISTS notification_dispatch (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    -- Recipient resolved at dispatch time
    recipient_type  TEXT NOT NULL CHECK (recipient_type IN ('guardian','staff','student')),
    recipient_id    UUID NOT NULL,
    channel         TEXT NOT NULL CHECK (channel IN ('push','email','whatsapp','sms')),
    template_code   TEXT,                              -- platform.whatsapp_template or email template id
    language        TEXT,
    variables       JSONB,
    provider_msg_id TEXT,                              -- BSP / FCM / SES id
    status          TEXT NOT NULL DEFAULT 'queued'
                      CHECK (status IN ('queued','sent','delivered','read','failed','rejected')),
    failure_reason  TEXT,
    related_type    TEXT,                              -- 'attendance' | 'payment' | 'announcement'
    related_id      UUID,
    queued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    read_at         TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS notif_recipient_idx ON notification_dispatch(recipient_type, recipient_id);
CREATE INDEX IF NOT EXISTS notif_status_idx    ON notification_dispatch(status, queued_at);

-- Announcements / circulars (per §7 Layer 2)
CREATE TABLE IF NOT EXISTS announcement (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    scope_type      TEXT NOT NULL CHECK (scope_type IN ('school','grade','section','custom')),
    scope_ids       UUID[],
    title           TEXT NOT NULL,
    body            TEXT NOT NULL,
    attachments     JSONB NOT NULL DEFAULT '[]'::jsonb,
    channels        TEXT[] NOT NULL DEFAULT ARRAY['push','email'],
    published_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    created_by_user_id UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS announcement_read (
    announcement_id UUID NOT NULL REFERENCES announcement(id) ON DELETE CASCADE,
    user_account_id UUID NOT NULL,
    read_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (announcement_id, user_account_id)
);

-- 1:1 messaging (teacher↔parent, audited per §7 Layer 2.2)
CREATE TABLE IF NOT EXISTS message_thread (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    subject_student_id UUID REFERENCES student(id),
    participants    UUID[] NOT NULL,                   -- user_account ids
    last_message_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS message (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id       UUID NOT NULL REFERENCES message_thread(id) ON DELETE CASCADE,
    sender_user_id  UUID NOT NULL,
    body            TEXT NOT NULL,
    file_id         UUID,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS message_thread_idx ON message(thread_id, sent_at);

-- ----------------------------------------------------------------------------
-- Audit log (per §7 0.6 + §16). Append-only.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGSERIAL PRIMARY KEY,
    school_id       UUID,
    actor_user_id   UUID,
    actor_label     TEXT,                               -- denormalised for fast read
    action          TEXT NOT NULL,                      -- 'student.update' | 'invoice.create' | 'mark.publish'
    target_type     TEXT,
    target_id       UUID,
    before_state    JSONB,
    after_state     JSONB,
    ip_address      INET,
    user_agent      TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS audit_target_idx ON audit_log(target_type, target_id);
CREATE INDEX IF NOT EXISTS audit_actor_time_idx ON audit_log(actor_user_id, occurred_at DESC);

-- ----------------------------------------------------------------------------
-- Feature flags (per §7 0.7)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS feature_flag (
    code            TEXT PRIMARY KEY,
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    description     TEXT,
    school_overrides JSONB NOT NULL DEFAULT '{}'::jsonb,  -- {school_id: bool}
    rollout_pct     INT NOT NULL DEFAULT 0 CHECK (rollout_pct BETWEEN 0 AND 100),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- Theming (per §7 0.8)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS school_theme (
    school_id       UUID PRIMARY KEY REFERENCES school(id) ON DELETE CASCADE,
    logo_file_id    UUID,
    favicon_file_id UUID,
    primary_color   TEXT NOT NULL DEFAULT '#1f3a8a',
    accent_color    TEXT NOT NULL DEFAULT '#f59e0b',
    parent_app_name TEXT,
    custom_domain   TEXT,
    email_from      TEXT,
    push_sender_id  TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- DPDP consent registry (per §16). One row per subject × purpose.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS consent_record (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    subject_type    TEXT NOT NULL CHECK (subject_type IN ('student','guardian','staff')),
    subject_id      UUID NOT NULL,
    purpose         TEXT NOT NULL,                      -- 'photo_publish' | 'whatsapp_marketing' | 'biometric'
    granted         BOOLEAN NOT NULL,
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    source          TEXT,                               -- 'admission_form' | 'parent_app' | 'paper'
    evidence_file_id UUID,
    UNIQUE (subject_type, subject_id, purpose, granted_at)
);

-- ----------------------------------------------------------------------------
-- Outbox (chain-scoped). Spring Modulith uses event_publication too; this is
-- for cross-module-but-business events.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  TEXT NOT NULL,
    aggregate_id    TEXT NOT NULL,
    event_type      TEXT NOT NULL,
    payload         JSONB NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS chain_outbox_unpublished_idx
  ON outbox (occurred_at) WHERE published_at IS NULL;

-- ----------------------------------------------------------------------------
-- Hardware: device registry (per §7 Layer 5)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    kind            TEXT NOT NULL CHECK (kind IN ('biometric','rfid_reader','gps_unit','tablet','printer')),
    vendor          TEXT,                               -- 'eSSL' | 'Mantra' | 'ZKTeco' | 'Teltonika'
    model           TEXT,
    serial_no       TEXT NOT NULL,
    location        TEXT,
    assigned_vehicle_id UUID REFERENCES vehicle(id),
    last_seen_at    TIMESTAMPTZ,
    api_key_hash    TEXT,                               -- device auth
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (school_id, kind, serial_no)
);
