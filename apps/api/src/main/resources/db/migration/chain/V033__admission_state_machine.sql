-- ----------------------------------------------------------------------------
-- The admissions state machine, as data (GAP-41, ADM-05)
--
-- Until now `AdmissionsRepository.transition` read the current state and then
-- wrote whatever target the caller asked for, so `lead -> enrolled` was
-- accepted and the UI was the only thing that knew better. The legal moves now
-- live in a table: the server refuses anything not listed, and a chain that
-- runs its funnel differently -- no entrance test, an extra interview stage --
-- edits rows rather than waiting on a deploy. Same argument as `role_perm`.
--
-- No `school_id`, and so no RLS policy: this is the chain's own vocabulary, not
-- one school's data, and every school in the chain reads the same rows. Should
-- a school ever need its own funnel, that is a new column and a new policy,
-- not a second table.
--
-- `requires_perm` is the permission the *move* needs, checked on top of the
-- endpoint's own `admission.decide`. It is what keeps `accepted -> enrolled`
-- out of the hands of a counsellor who may decide but may not enrol.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admission_transition (
    from_state    TEXT NOT NULL,
    to_state      TEXT NOT NULL,
    requires_perm TEXT NOT NULL DEFAULT 'admission.decide',
    note          TEXT,
    PRIMARY KEY (from_state, to_state),
    CHECK (from_state <> to_state)
);

-- The flow of design doc §13. Three kinds of move, kept apart so the table
-- reads as the diagram it came from.
INSERT INTO admission_transition (from_state, to_state, requires_perm, note) VALUES
    -- Forward: the happy path.
    ('lead',                'application_started', 'admission.decide', 'forward'),
    ('application_started', 'document_pending',    'admission.decide', 'forward'),
    ('document_pending',    'fee_pending',         'admission.decide', 'forward'),
    ('fee_pending',         'review',              'admission.decide', 'forward'),
    ('review',              'test_scheduled',      'admission.decide', 'forward'),
    ('review',              'offered',             'admission.decide', 'forward: school runs no entrance test'),
    ('review',              'waitlist',            'admission.decide', 'forward'),
    ('test_scheduled',      'test_done',           'admission.decide', 'forward'),
    ('test_done',           'offered',             'admission.decide', 'forward'),
    ('test_done',           'waitlist',            'admission.decide', 'forward'),
    ('offered',             'accepted',            'admission.decide', 'forward'),
    ('offered',             'waitlist',            'admission.decide', 'forward: offer declined, back to the pool'),
    ('waitlist',            'offered',             'admission.decide', 'forward: promoted off the waitlist'),
    ('accepted',            'enrolled',            'admission.enrol',  'forward: seat confirmed'),
    ('lapsed',              'waitlist',            'admission.decide', 'forward: §13 sends a lapsed offer back to the pool'),

    -- Back: a correction, only while the decision is still the school's own.
    -- Nothing steps back out of offered/accepted, because the family has
    -- already been told.
    ('document_pending',    'application_started', 'admission.decide', 'correction'),
    ('fee_pending',         'document_pending',    'admission.decide', 'correction'),
    ('review',              'fee_pending',         'admission.decide', 'correction'),
    ('test_scheduled',      'review',              'admission.decide', 'correction'),
    ('test_done',           'test_scheduled',      'admission.decide', 'correction: re-test'),

    -- Out: rejection and lapse close a file from wherever it stands.
    ('lead',                'rejected',            'admission.decide', 'exit'),
    ('application_started', 'rejected',            'admission.decide', 'exit'),
    ('document_pending',    'rejected',            'admission.decide', 'exit'),
    ('fee_pending',         'rejected',            'admission.decide', 'exit'),
    ('review',              'rejected',            'admission.decide', 'exit'),
    ('test_scheduled',      'rejected',            'admission.decide', 'exit'),
    ('test_done',           'rejected',            'admission.decide', 'exit'),
    ('offered',             'rejected',            'admission.decide', 'exit'),
    ('waitlist',            'rejected',            'admission.decide', 'exit'),
    ('lead',                'lapsed',              'admission.decide', 'exit'),
    ('application_started', 'lapsed',              'admission.decide', 'exit'),
    ('document_pending',    'lapsed',              'admission.decide', 'exit'),
    ('fee_pending',         'lapsed',              'admission.decide', 'exit'),
    ('review',              'lapsed',              'admission.decide', 'exit'),
    ('test_scheduled',      'lapsed',              'admission.decide', 'exit'),
    ('test_done',           'lapsed',              'admission.decide', 'exit'),
    ('offered',             'lapsed',              'admission.decide', 'exit: offer expired'),
    ('accepted',            'lapsed',              'admission.decide', 'exit: seat not confirmed'),
    ('waitlist',            'lapsed',              'admission.decide', 'exit')
ON CONFLICT (from_state, to_state) DO NOTHING;

-- `enrolled` and `rejected` are terminal: neither appears as a from_state.
