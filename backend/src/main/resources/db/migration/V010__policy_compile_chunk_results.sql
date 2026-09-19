-- F6 follow-up: per-chunk compile diagnostics + COMPLETED_NO_RULES

ALTER TABLE policy_compilations DROP CONSTRAINT IF EXISTS policy_compilations_status_check;
ALTER TABLE policy_compilations
    ADD CONSTRAINT policy_compilations_status_check
        CHECK (status IN (
            'QUEUED', 'RUNNING', 'COMPLETED', 'COMPLETED_NO_RULES', 'FAILED', 'CANCELLED'
        ));

CREATE TABLE IF NOT EXISTS policy_compile_chunk_results (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    compilation_id  UUID NOT NULL REFERENCES policy_compilations (id) ON DELETE CASCADE,
    document_id     UUID NOT NULL,
    chunk_id        UUID NOT NULL,
    status          TEXT NOT NULL
        CHECK (status IN (
            'SKIPPED_PREFILTER',
            'LLM_OK',
            'LLM_TIMEOUT',
            'LLM_SCHEMA_ERROR',
            'LLM_EMPTY',
            'REJECTED_VALIDATION'
        )),
    reason          TEXT,
    latency_ms      INT NOT NULL DEFAULT 0,
    rules_proposed  INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (compilation_id, chunk_id)
);

CREATE INDEX IF NOT EXISTS idx_policy_compile_chunk_results_comp
    ON policy_compile_chunk_results (compilation_id, status);

DO $$
BEGIN
    EXECUTE 'ALTER TABLE policy_compile_chunk_results ENABLE ROW LEVEL SECURITY';
    EXECUTE 'ALTER TABLE policy_compile_chunk_results FORCE ROW LEVEL SECURITY';
    EXECUTE 'DROP POLICY IF EXISTS tenant_isolation ON policy_compile_chunk_results';
    EXECUTE
        'CREATE POLICY tenant_isolation ON policy_compile_chunk_results
           USING (tenant_id = app_current_tenant_id())
           WITH CHECK (tenant_id = app_current_tenant_id())';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON policy_compile_chunk_results TO sv_app';
END $$;
