-- F6/F7 addendum: Live Rules soft-delete, simulation examples, DEMO_FIXTURE origin,
-- removal-draft metadata on policy_sets.

ALTER TABLE policy_rules
    ADD COLUMN IF NOT EXISTS deleted_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deleted_by     UUID,
    ADD COLUMN IF NOT EXISTS delete_reason  TEXT,
    ADD COLUMN IF NOT EXISTS simulation_examples JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_policy_rules_deleted
    ON policy_rules (tenant_id, policy_set_id)
    WHERE deleted_at IS NOT NULL;

-- Allow DEMO_FIXTURE origin (drop/recreate check)
ALTER TABLE policy_rules DROP CONSTRAINT IF EXISTS policy_rules_origin_check;
ALTER TABLE policy_rules
    ADD CONSTRAINT policy_rules_origin_check
        CHECK (origin IN ('LLM', 'LLM_MOCK', 'MANUAL', 'DEMO_FIXTURE'));

ALTER TABLE policy_sets
    ADD COLUMN IF NOT EXISTS meta JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN policy_sets.meta IS
    'Live-rules addendum: removalFromActiveId, removalPendingRuleIds, confirmEmptyPolicy, etc.';
