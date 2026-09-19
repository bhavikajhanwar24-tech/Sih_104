-- F5 addendum: minimal policy_sets / policy_rules so document permanent-delete
-- can enforce citation checks. F6/F7 will extend these tables.

CREATE TABLE policy_sets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants (id),
    name        TEXT NOT NULL DEFAULT 'default',
    status      TEXT NOT NULL
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'SUPERSEDED')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE policy_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    policy_set_id   UUID NOT NULL REFERENCES policy_sets (id) ON DELETE CASCADE,
    title           TEXT NOT NULL,
    -- F6 citations: { "documentId": "<uuid>", "chunkId": "...", "sourceDeleted": true }
    source          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_policy_sets_tenant_status ON policy_sets (tenant_id, status);
CREATE INDEX idx_policy_rules_tenant_set ON policy_rules (tenant_id, policy_set_id);
CREATE INDEX idx_policy_rules_source_document
    ON policy_rules ((source ->> 'documentId'));

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['policy_sets', 'policy_rules']
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
