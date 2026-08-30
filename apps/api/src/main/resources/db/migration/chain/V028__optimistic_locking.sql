-- Optimistic locking for the records two people edit as a form.
--
-- A `version` column is the right tool for exactly one shape of write: read a
-- whole record, change some fields, write the whole record back. Two editors
-- overlapping there means the second save silently discards the first — work a
-- human typed, gone with no error and no trace.
--
-- It is deliberately NOT applied everywhere. The other contended writes in
-- this schema are already safe, or want a different fix:
--
--   * Relative arithmetic — `fee_invoice.paid = paid + ?`,
--     `total = total + ?` — is computed inside the database, which serialises
--     the row. Two concurrent payments both land. A version column here would
--     add spurious conflicts and fix nothing.
--
--   * State transitions — `report_card.status`, `academic_year.status`,
--     `enrolment.status` — are better served by a conditional UPDATE naming
--     the state being left (`WHERE id = ? AND status = 'locked'`), which is
--     stricter than a counter and needs no API change. See
--     ReportCardService and SchoolRepository.
--
--   * Single-field policy toggles (attendance_policy, dunning_policy,
--     feature_flag) are idempotent settings, not authored documents. A lost
--     update there costs a re-toggle, not a rewrite.
--
-- The two records below are neither: they hold work somebody composed.
--
--   role            name, description and the screen list. Two administrators
--                   editing what a role may reach, one silently winning, is a
--                   permissions change nobody decided on.
--
--   fee_structure   `replaceLines` deletes every line and re-inserts, so an
--                   overlapping save does not merge or partially lose — it
--                   drops an entire fee schedule.
--
-- Contract: the client sends the version it read; the UPDATE carries
-- `AND version = ?` and bumps it. Zero rows affected means somebody else got
-- there first, and the caller is told so (409) rather than being allowed to
-- believe their save landed.

ALTER TABLE role
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE fee_structure
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
