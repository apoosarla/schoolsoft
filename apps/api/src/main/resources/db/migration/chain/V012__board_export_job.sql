-- ============================================================================
-- Board Integration outbound adapter framework (per §1.9). CBSE: UDISE+ XML
-- export, Pariksha Sangam mapping. Cambridge: CIE Direct candidate
-- registration, syllabus entries, statement-of-entry retrieval. Each export
-- attempt is a queued/tracked job so failures are visible and retryable
-- (matches Risk R1's CSV-export fallback posture — no export is fire-and-forget).
-- ============================================================================

CREATE TABLE IF NOT EXISTS board_export_job (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id       UUID NOT NULL REFERENCES school(id),
    board_code      TEXT NOT NULL,                     -- 'CBSE' | 'CIE'
    export_type     TEXT NOT NULL
                      CHECK (export_type IN ('udise_xml','pariksha_sangam','cie_candidate_registration','cie_syllabus_entry','cie_statement_of_entry')),
    academic_year_id UUID REFERENCES academic_year(id),
    section_id      UUID REFERENCES section(id),
    student_id      UUID REFERENCES student(id),
    status          TEXT NOT NULL DEFAULT 'queued'
                      CHECK (status IN ('queued','processing','completed','failed')),
    request_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_payload  JSONB,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS board_export_job_school_idx ON board_export_job(school_id, status);
