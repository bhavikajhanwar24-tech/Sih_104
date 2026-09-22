-- F12: Per-call explainability records + forensic dossier digests (privacy-preserving).
-- No audio, no transcripts, no raw account numbers — scores/enums/redacted labels only.

-- Sampling knobs (tenant-editable; F15 retention uses retention_days).
ALTER TABLE tenant_settings
    ADD COLUMN IF NOT EXISTS explain_score_delta DOUBLE PRECISION NOT NULL DEFAULT 0.05
        CHECK (explain_score_delta >= 0 AND explain_score_delta <= 1),
    ADD COLUMN IF NOT EXISTS explain_tick_max_gap_ms INT NOT NULL DEFAULT 5000
        CHECK (explain_tick_max_gap_ms >= 1000 AND explain_tick_max_gap_ms <= 60000);

-- ---------------------------------------------------------------------------
-- session_ticks — sampled risk timeline
-- ---------------------------------------------------------------------------
CREATE TABLE session_ticks (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (id),
    session_id          UUID NOT NULL,
    t_ms                BIGINT NOT NULL,
    score               DOUBLE PRECISION NOT NULL,
    level               TEXT NOT NULL,
    family_scores       JSONB NOT NULL DEFAULT '{}'::jsonb,
    missing_families    TEXT[] NOT NULL DEFAULT '{}',
    fired_rule_ids      TEXT[] NOT NULL DEFAULT '{}',
    llm_state           TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_session_ticks_session_t UNIQUE (tenant_id, session_id, t_ms)
);

CREATE INDEX idx_session_ticks_tenant_session ON session_ticks (tenant_id, session_id, t_ms);

-- ---------------------------------------------------------------------------
-- session_reasons — typed explainability rows
-- ---------------------------------------------------------------------------
CREATE TABLE session_reasons (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (id),
    session_id          UUID NOT NULL,
    seq                 INT NOT NULL,
    t_ms                BIGINT NOT NULL,
    code                TEXT NOT NULL,
    title               TEXT NOT NULL,
    detail              TEXT NOT NULL,
    contribution        DOUBLE PRECISION,
    family              TEXT,
    severity            TEXT,
    evidence            JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_clause       JSONB,
    rule_id             TEXT,
    policy_version      INT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_session_reasons_seq UNIQUE (tenant_id, session_id, seq)
);

CREATE INDEX idx_session_reasons_tenant_session ON session_reasons (tenant_id, session_id, seq);

-- ---------------------------------------------------------------------------
-- session_extractions — ask / categories enums only (no free transcript)
-- ---------------------------------------------------------------------------
CREATE TABLE session_extractions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (id),
    session_id          UUID NOT NULL,
    t_ms                BIGINT NOT NULL,
    ask                 JSONB NOT NULL DEFAULT '{}'::jsonb,
    categories          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_session_extractions_tenant_session ON session_extractions (tenant_id, session_id, t_ms);

-- ---------------------------------------------------------------------------
-- forensic_dossiers — generated packages (JSON digest + PDF hash); no binary blob
-- ---------------------------------------------------------------------------
CREATE TABLE forensic_dossiers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (id),
    session_id          UUID NOT NULL,
    generated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    generated_by        TEXT,
    content_sha256      TEXT NOT NULL,
    pdf_sha256          TEXT,
    audit_seq_from      BIGINT,
    audit_seq_to        BIGINT,
    audit_chain_valid   BOOLEAN,
    payload             JSONB NOT NULL,
    CONSTRAINT uq_forensic_dossiers_hash UNIQUE (tenant_id, content_sha256)
);

CREATE INDEX idx_forensic_dossiers_tenant_session ON forensic_dossiers (tenant_id, session_id, generated_at DESC);
CREATE INDEX idx_forensic_dossiers_tenant_hash ON forensic_dossiers (tenant_id, content_sha256);

-- Optional review flags on call_sessions (F16 will expand; stub columns for list filters)
ALTER TABLE call_sessions
    ADD COLUMN IF NOT EXISTS review_status TEXT
        CHECK (review_status IS NULL OR review_status IN ('UNREVIEWED', 'FALSE_POSITIVE', 'CONFIRMED_FRAUD')),
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reviewed_by UUID;

-- ---------------------------------------------------------------------------
-- RLS
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'session_ticks', 'session_reasons', 'session_extractions', 'forensic_dossiers'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I
               USING (tenant_id = app_current_tenant_id())
               WITH CHECK (tenant_id = app_current_tenant_id())',
            t
        );
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO sv_app', t);
    END LOOP;
END $$;
