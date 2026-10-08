-- ----------------------------------------------------------------------------
-- A gate read is evidence, not a decision (ATT-08).
--
-- A biometric or RFID punch used to go through the same upsert as a teacher's
-- mark. A device that came back from a day offline and replayed its backlog
-- therefore wrote 'present' over whatever a person had recorded in the
-- meantime — a teacher's 'absent', an approved leave — and, where the register
-- had been signed off, was refused outright, so the bridge retried it forever.
--
-- The write is now one-way: a device fills a day nobody has marked and never
-- changes one somebody has. What it saw is still kept, on the record it did
-- not get to write: a card read at the gate on a day the register says absent
-- is a disagreement the school should be able to see.
-- ----------------------------------------------------------------------------

ALTER TABLE attendance_record
    -- When the first device event for this student and day arrived. Not the
    -- time of the punch: the bridge sends a date, and a replayed event arrives
    -- days after it happened.
    ADD COLUMN IF NOT EXISTS gate_seen_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS gate_source  TEXT CHECK (gate_source IN ('biometric','rfid'));
