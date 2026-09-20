-- F6 empty-LLM fix: finer chunk outcome statuses + optional meta for LLM telemetry

ALTER TABLE policy_compile_chunk_results
    DROP CONSTRAINT IF EXISTS policy_compile_chunk_results_status_check;

ALTER TABLE policy_compile_chunk_results
    ADD CONSTRAINT policy_compile_chunk_results_status_check
        CHECK (status IN (
            'SKIPPED_PREFILTER',
            'LLM_OK',
            'LLM_OK_WITH_RULES',
            'LLM_OK_EMPTY',
            'LLM_TIMEOUT',
            'LLM_TRUNCATED',
            'LLM_SCHEMA_ERROR',
            'LLM_UNAVAILABLE',
            'LLM_EMPTY',
            'LLM_EMPTY_SUSPECT',
            'REJECTED_VALIDATION'
        ));

ALTER TABLE policy_compile_chunk_results
    ADD COLUMN IF NOT EXISTS meta JSONB NOT NULL DEFAULT '{}'::jsonb;
