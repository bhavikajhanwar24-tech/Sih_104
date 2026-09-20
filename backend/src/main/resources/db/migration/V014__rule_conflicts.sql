-- F6 addendum: rule conflict detection + resolution persistence

CREATE TABLE IF NOT EXISTS rule_conflicts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    policy_set_id   UUID REFERENCES policy_sets (id) ON DELETE SET NULL,
    rule_a_id       TEXT NOT NULL,
    rule_b_id       TEXT NOT NULL,
    rule_a_pk       UUID,
    rule_b_pk       UUID,
    conflict_type   TEXT NOT NULL
        CHECK (conflict_type IN (
            'THRESHOLD_CONFLICT', 'LEVEL_CONFLICT', 'DUPLICATE',
            'SUBSUMED', 'OPPOSING', 'ADVISORY'
        )),
    status          TEXT NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'RESOLVED')),
    resolution      TEXT
        CHECK (resolution IS NULL OR resolution IN (
            'KEEP_EXISTING', 'KEEP_NEW', 'KEEP_BOTH', 'MERGE_EDIT', 'DEFER'
        )),
    reason          TEXT,
    summary         TEXT NOT NULL DEFAULT '',
    detail          JSONB NOT NULL DEFAULT '{}'::jsonb,
    advisory        BOOLEAN NOT NULL DEFAULT false,
    resolved_by     UUID,
    resolved_at     TIMESTAMPTZ,
    content_sha_a   TEXT,
    content_sha_b   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rule_conflicts_tenant_status
    ON rule_conflicts (tenant_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_rule_conflicts_set
    ON rule_conflicts (tenant_id, policy_set_id)
    WHERE policy_set_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_rule_conflicts_open_pair
    ON rule_conflicts (tenant_id, policy_set_id, rule_a_id, rule_b_id, conflict_type)
    WHERE status = 'OPEN';

DO $$
BEGIN
    EXECUTE 'ALTER TABLE rule_conflicts ENABLE ROW LEVEL SECURITY';
    EXECUTE 'ALTER TABLE rule_conflicts FORCE ROW LEVEL SECURITY';
    EXECUTE 'DROP POLICY IF EXISTS tenant_isolation ON rule_conflicts';
    EXECUTE
        'CREATE POLICY tenant_isolation ON rule_conflicts
           USING (tenant_id = app_current_tenant_id())
           WITH CHECK (tenant_id = app_current_tenant_id())';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON rule_conflicts TO sv_app';
END $$;

-- Soft marker for rules replaced via conflict resolution (also in rule_body)
ALTER TABLE policy_rules
    ADD COLUMN IF NOT EXISTS replaced_by_rule_id TEXT;

COMMENT ON TABLE rule_conflicts IS
    'F6: deterministic (+ optional advisory) rule conflicts and operator resolutions';
