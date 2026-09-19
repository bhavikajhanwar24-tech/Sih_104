-- F6: policy compilation, versioned sets, keywords, facts

-- Extend policy_sets
ALTER TABLE policy_sets
    ADD COLUMN IF NOT EXISTS version          INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS created_by       UUID,
    ADD COLUMN IF NOT EXISTS submitted_by     UUID,
    ADD COLUMN IF NOT EXISTS approved_by      UUID,
    ADD COLUMN IF NOT EXISTS approved_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS comment          TEXT,
    ADD COLUMN IF NOT EXISTS content_sha256   TEXT,
    ADD COLUMN IF NOT EXISTS compilation_id   UUID,
    ADD COLUMN IF NOT EXISTS supersedes_id    UUID REFERENCES policy_sets (id);

-- Allow REJECTED status
ALTER TABLE policy_sets DROP CONSTRAINT IF EXISTS policy_sets_status_check;
ALTER TABLE policy_sets
    ADD CONSTRAINT policy_sets_status_check
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'SUPERSEDED', 'REJECTED'));

-- One ACTIVE set per tenant
CREATE UNIQUE INDEX IF NOT EXISTS uq_policy_sets_one_active
    ON policy_sets (tenant_id)
    WHERE status = 'ACTIVE';

-- Approver must differ from submitter when both set
CREATE OR REPLACE FUNCTION fn_policy_set_approver_ne_submitter()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status IN ('ACTIVE', 'REJECTED')
       AND NEW.approved_by IS NOT NULL
       AND NEW.submitted_by IS NOT NULL
       AND NEW.approved_by = NEW.submitted_by THEN
        RAISE EXCEPTION 'POLICY_APPROVER must differ from submitter';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_policy_set_approver_ne_submitter ON policy_sets;
CREATE TRIGGER trg_policy_set_approver_ne_submitter
    BEFORE UPDATE OR INSERT ON policy_sets
    FOR EACH ROW EXECUTE FUNCTION fn_policy_set_approver_ne_submitter();

-- Extend policy_rules with full DSL columns
ALTER TABLE policy_rules
    ADD COLUMN IF NOT EXISTS rule_id        TEXT,
    ADD COLUMN IF NOT EXISTS description    TEXT,
    ADD COLUMN IF NOT EXISTS applies_to     JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS when_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS then_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS severity       TEXT NOT NULL DEFAULT 'MEDIUM'
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    ADD COLUMN IF NOT EXISTS status         TEXT NOT NULL DEFAULT 'PROPOSED'
        CHECK (status IN ('PROPOSED', 'ACCEPTED', 'EDITED', 'REJECTED')),
    ADD COLUMN IF NOT EXISTS origin         TEXT NOT NULL DEFAULT 'LLM'
        CHECK (origin IN ('LLM', 'LLM_MOCK', 'MANUAL')),
    ADD COLUMN IF NOT EXISTS warnings       JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS rule_body      JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS updated_at     TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE UNIQUE INDEX IF NOT EXISTS uq_policy_rules_set_rule_id
    ON policy_rules (policy_set_id, rule_id)
    WHERE rule_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS policy_compilations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    mode            TEXT NOT NULL CHECK (mode IN ('FULL', 'INCREMENTAL')),
    status          TEXT NOT NULL
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    document_ids    UUID[] NOT NULL DEFAULT '{}',
    policy_set_id   UUID REFERENCES policy_sets (id),
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    error           TEXT,
    progress        JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_policy_compilations_tenant
    ON policy_compilations (tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS policy_compilation_chunks (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (id),
    compilation_id      UUID NOT NULL REFERENCES policy_compilations (id) ON DELETE CASCADE,
    document_id         UUID NOT NULL,
    chunk_id            UUID NOT NULL,
    status              TEXT NOT NULL
        CHECK (status IN ('PENDING', 'SKIPPED', 'RUNNING', 'DONE', 'FAILED')),
    skip_reason         TEXT,
    rules_proposed      INT NOT NULL DEFAULT 0,
    rules_rejected      INT NOT NULL DEFAULT 0,
    error               TEXT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (compilation_id, chunk_id)
);

CREATE INDEX IF NOT EXISTS idx_policy_compilation_chunks_comp
    ON policy_compilation_chunks (compilation_id, status);

CREATE TABLE IF NOT EXISTS policy_keywords (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    policy_set_id   UUID NOT NULL REFERENCES policy_sets (id) ON DELETE CASCADE,
    term            TEXT NOT NULL,
    lang            TEXT NOT NULL DEFAULT 'en',
    category        TEXT NOT NULL
        CHECK (category IN ('URGENCY', 'SECRECY', 'AUTHORITY', 'PAYMENT', 'CREDENTIAL', 'CUSTOM')),
    weight          DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    source_rule_id  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_policy_keywords_set
    ON policy_keywords (tenant_id, policy_set_id, category);

CREATE TABLE IF NOT EXISTS policy_facts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    policy_set_id   UUID NOT NULL REFERENCES policy_sets (id) ON DELETE CASCADE,
    source_rule_id  TEXT NOT NULL,
    text            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_policy_facts_set_rule
    ON policy_facts (tenant_id, policy_set_id, source_rule_id);

-- Link compilations FK after both tables exist
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_policy_sets_compilation'
    ) THEN
        ALTER TABLE policy_sets
            ADD CONSTRAINT fk_policy_sets_compilation
            FOREIGN KEY (compilation_id) REFERENCES policy_compilations (id);
    END IF;
END $$;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'policy_compilations',
        'policy_compilation_chunks',
        'policy_keywords',
        'policy_facts'
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
