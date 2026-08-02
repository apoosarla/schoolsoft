-- ============================================================================
-- Fees, Payments, GST IRN, double-entry shadow Ledger. Per §14 + §7 Layer 4.
-- ============================================================================

-- Fee head taxonomy (Tuition, Lab, Transport, Library, Exam, etc.)
CREATE TABLE IF NOT EXISTS fee_head (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    code            TEXT NOT NULL,                     -- 'TUITION' | 'TRANSPORT' | 'LAB' | 'EXAM'
    name            TEXT NOT NULL,
    is_recurring    BOOLEAN NOT NULL DEFAULT TRUE,
    gst_rate_pct    NUMERIC(5,2) NOT NULL DEFAULT 0,
    hsn_sac         TEXT,
    income_account_code TEXT,                          -- ledger account code (mapped to Tally)
    UNIQUE (school_id, code)
);

-- Per-grade × AY structure. Concession layers stacked separately.
CREATE TABLE IF NOT EXISTS fee_structure (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    grade_id        UUID NOT NULL REFERENCES grade(id),
    academic_year_id UUID NOT NULL REFERENCES academic_year(id),
    name            TEXT NOT NULL,
    schedule        JSONB NOT NULL,                    -- billing cadence: monthly/quarterly/term
    UNIQUE (school_id, grade_id, academic_year_id, name)
);

CREATE TABLE IF NOT EXISTS fee_structure_line (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fee_structure_id UUID NOT NULL REFERENCES fee_structure(id) ON DELETE CASCADE,
    fee_head_id     UUID NOT NULL REFERENCES fee_head(id),
    amount          NUMERIC(12,2) NOT NULL,
    UNIQUE (fee_structure_id, fee_head_id)
);

-- Discounts / scholarships / sibling concessions.
CREATE TABLE IF NOT EXISTS fee_concession (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    student_id      UUID NOT NULL REFERENCES student(id) ON DELETE CASCADE,
    academic_year_id UUID NOT NULL REFERENCES academic_year(id),
    kind            TEXT NOT NULL CHECK (kind IN ('sibling','scholarship','staff_ward','manual','need_based')),
    pct             NUMERIC(5,2),
    flat_amount     NUMERIC(12,2),
    applies_to_head_id UUID REFERENCES fee_head(id),    -- nullable → all heads
    notes           TEXT,
    approved_by_staff_id UUID REFERENCES staff(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Invoice is the bill to the payor. One invoice per billing cycle.
CREATE TABLE IF NOT EXISTS fee_invoice (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    student_id      UUID NOT NULL REFERENCES student(id),
    invoice_no      TEXT NOT NULL,
    cycle_label     TEXT NOT NULL,                     -- 'Apr 2026' | 'Term1 2026-27'
    issued_on       DATE NOT NULL DEFAULT CURRENT_DATE,
    due_on          DATE NOT NULL,
    subtotal        NUMERIC(12,2) NOT NULL,
    gst             NUMERIC(12,2) NOT NULL DEFAULT 0,
    total           NUMERIC(12,2) NOT NULL,
    paid            NUMERIC(12,2) NOT NULL DEFAULT 0,
    status          TEXT NOT NULL DEFAULT 'open'
                      CHECK (status IN ('draft','open','partial','paid','overdue','cancelled','refunded')),
    -- GST e-invoice fields (per §16 + R-resolution)
    irn             TEXT,
    irn_qr          TEXT,
    irn_signed_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (school_id, invoice_no)
);
CREATE INDEX IF NOT EXISTS fee_invoice_student_idx ON fee_invoice(student_id);
CREATE INDEX IF NOT EXISTS fee_invoice_due_status_idx ON fee_invoice(status, due_on);

CREATE TABLE IF NOT EXISTS fee_invoice_line (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fee_invoice_id  UUID NOT NULL REFERENCES fee_invoice(id) ON DELETE CASCADE,
    fee_head_id     UUID NOT NULL REFERENCES fee_head(id),
    description     TEXT NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    discount        NUMERIC(12,2) NOT NULL DEFAULT 0,
    gst             NUMERIC(12,2) NOT NULL DEFAULT 0
);

-- Payment is the cash event. May span gateways. Idempotent on idempotency_key.
CREATE TABLE IF NOT EXISTS payment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    fee_invoice_id  UUID NOT NULL REFERENCES fee_invoice(id),
    amount          NUMERIC(12,2) NOT NULL,
    gateway         TEXT NOT NULL,                     -- 'razorpay' | 'cashfree' | 'payu' | 'cash' | 'cheque'
    gateway_order_id TEXT,
    gateway_payment_id TEXT,
    method          TEXT,                              -- 'upi' | 'card' | 'netbanking' | 'cash'
    status          TEXT NOT NULL DEFAULT 'pending'
                      CHECK (status IN ('pending','authorized','captured','failed','refunded','partial_refund')),
    idempotency_key TEXT NOT NULL UNIQUE,
    captured_at     TIMESTAMPTZ,
    raw_payload     JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS payment_invoice_idx ON payment(fee_invoice_id);

-- Double-entry shadow ledger. Every payment / refund / discount / waiver
-- emits balanced journal lines.
CREATE TABLE IF NOT EXISTS ledger_account (
    code            TEXT PRIMARY KEY,                  -- 'BANK_HDFC' | 'FEE_RECEIVABLE' | 'FEE_INCOME_TUITION' | 'DISCOUNT'
    name            TEXT NOT NULL,
    type            TEXT NOT NULL CHECK (type IN ('asset','liability','equity','income','expense')),
    tally_ledger_name TEXT
);

CREATE TABLE IF NOT EXISTS ledger_entry (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    posted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    journal_id      UUID NOT NULL,                     -- groups balanced legs
    account_code    TEXT NOT NULL REFERENCES ledger_account(code),
    debit           NUMERIC(14,2) NOT NULL DEFAULT 0,
    credit          NUMERIC(14,2) NOT NULL DEFAULT 0,
    narration       TEXT,
    source_type     TEXT NOT NULL,                     -- 'payment' | 'invoice' | 'refund' | 'discount'
    source_id       UUID NOT NULL,
    CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0))
);
CREATE INDEX IF NOT EXISTS ledger_journal_idx ON ledger_entry(journal_id);
CREATE INDEX IF NOT EXISTS ledger_source_idx ON ledger_entry(source_type, source_id);
