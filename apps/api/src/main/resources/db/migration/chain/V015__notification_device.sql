-- ============================================================================
-- Push device registry (per §10). A mobile app session knows its own
-- user_account id, so tokens are keyed by that; the notification pipeline
-- resolves recipients by (recipient_type, recipient_id), which is reachable
-- from here via user_account(subject_type, subject_id).
-- ============================================================================

CREATE TABLE IF NOT EXISTS notification_device (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_account_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    token           TEXT NOT NULL,                     -- FCM registration token
    platform        TEXT NOT NULL CHECK (platform IN ('android','ios','web')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- FCM tokens are unique per app install; if a device is handed to another
    -- account the token moves rather than being duplicated.
    UNIQUE (token)
);
CREATE INDEX IF NOT EXISTS notification_device_user_idx ON notification_device(user_account_id);
