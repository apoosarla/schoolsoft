-- ============================================================================
-- GAP-35: wire the notification producers.
--
-- Three shapes were missing before a producer could be pointed at
-- NotificationService:
--
--  1. A dispatch had no idempotency key. "Notify the parent that the child is
--     absent" must fire once per child per day however many times the register
--     is re-saved (ATT-03), and re-publishing an announcement must not blast
--     the school a second time (COMM-06). The key is the event, not the row,
--     so it is unique per (key, recipient, channel) and the insert is
--     ON CONFLICT DO NOTHING — a second attempt writes nothing and sends
--     nothing rather than racing.
--
--  2. An admissions applicant is not yet a guardian row: the acknowledgement
--     for a public enquiry (ADM-01) goes to contact details that live on
--     admission_application. 'applicant' names that recipient, with the
--     application's own id.
--
--  3. An announcement had no way to say it is an emergency, so "emergency
--     broadcast" (COMM-06) was not expressible. Quiet hours and the override
--     that would read this column are still GAP-21/COMM-05.
-- ============================================================================

ALTER TABLE notification_dispatch ADD COLUMN IF NOT EXISTS dedupe_key TEXT;

ALTER TABLE notification_dispatch DROP CONSTRAINT IF EXISTS notification_dispatch_recipient_type_check;
ALTER TABLE notification_dispatch ADD CONSTRAINT notification_dispatch_recipient_type_check
    CHECK (recipient_type IN ('guardian','staff','student','applicant'));

CREATE UNIQUE INDEX IF NOT EXISTS notif_dedupe_idx
    ON notification_dispatch (dedupe_key, recipient_type, recipient_id, channel)
    WHERE dedupe_key IS NOT NULL;

-- Delivery stats for one announcement / one invoice read this way.
CREATE INDEX IF NOT EXISTS notif_related_idx
    ON notification_dispatch (related_type, related_id);

ALTER TABLE announcement ADD COLUMN IF NOT EXISTS priority TEXT NOT NULL DEFAULT 'normal';
ALTER TABLE announcement DROP CONSTRAINT IF EXISTS announcement_priority_check;
ALTER TABLE announcement ADD CONSTRAINT announcement_priority_check
    CHECK (priority IN ('normal','emergency'));
