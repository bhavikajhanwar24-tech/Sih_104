-- F17: Public integration surface — API keys, webhooks, transaction contexts.

ALTER TABLE tenant_settings
    ADD COLUMN IF NOT EXISTS integrations_docs_enabled BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    prefix          TEXT NOT NULL,
    key_hash        TEXT NOT NULL,
    scopes          TEXT[] NOT NULL DEFAULT '{}',
    created_by      UUID REFERENCES users (id),
    last_used_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
    usage_count     BIGINT NOT NULL DEFAULT 0,
    rate_limit_rps  INT NOT NULL DEFAULT 60,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    CONSTRAINT api_keys_status_check CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT api_keys_prefix_unique UNIQUE (prefix)
);

CREATE INDEX IF NOT EXISTS idx_api_keys_tenant ON api_keys (tenant_id);
CREATE INDEX IF NOT EXISTS idx_api_keys_hash ON api_keys (key_hash);

CREATE TABLE IF NOT EXISTS webhook_endpoints (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    url             TEXT NOT NULL,
    secret          TEXT NOT NULL,
    events          TEXT[] NOT NULL DEFAULT '{}',
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
    description     TEXT,
    created_by      UUID REFERENCES users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT webhook_endpoints_status_check CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX IF NOT EXISTS idx_webhook_endpoints_tenant ON webhook_endpoints (tenant_id);

CREATE TABLE IF NOT EXISTS webhook_deliveries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    endpoint_id     UUID NOT NULL REFERENCES webhook_endpoints (id) ON DELETE CASCADE,
    event_type      TEXT NOT NULL,
    payload         JSONB NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    attempt_count   INT NOT NULL DEFAULT 0,
    max_attempts    INT NOT NULL DEFAULT 8,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_status_code INT,
    last_error      TEXT,
    response_body   TEXT,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT webhook_deliveries_status_check
        CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED', 'DEAD'))
);

CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_poll
    ON webhook_deliveries (status, next_attempt_at)
    WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_endpoint
    ON webhook_deliveries (endpoint_id, created_at DESC);

CREATE TABLE IF NOT EXISTS transaction_contexts (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    session_id              TEXT,
    call_reference          TEXT,
    action_type             TEXT NOT NULL,
    amount_inr              NUMERIC(18, 2),
    beneficiary_ref         TEXT,
    requester_employee_ref  TEXT,
    channel                 TEXT,
    source                  TEXT NOT NULL DEFAULT 'CORE_SYSTEM',
    decision                TEXT NOT NULL,
    level                   TEXT,
    reasons                 JSONB NOT NULL DEFAULT '[]'::jsonb,
    gate_check_ms           INT,
    api_key_id              UUID REFERENCES api_keys (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transaction_contexts_decision_check
        CHECK (decision IN ('ALLOW', 'CHALLENGE', 'BLOCK')),
    CONSTRAINT transaction_contexts_source_check
        CHECK (source IN ('CORE_SYSTEM', 'SPEECH', 'MANUAL'))
);

CREATE INDEX IF NOT EXISTS idx_transaction_contexts_tenant
    ON transaction_contexts (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transaction_contexts_session
    ON transaction_contexts (tenant_id, session_id);

-- Expand cross-channel channels for F17 inbound events (CHAT / LOGIN).
ALTER TABLE cross_channel_events
    DROP CONSTRAINT IF EXISTS cross_channel_events_channel_check;
ALTER TABLE cross_channel_events
    ADD CONSTRAINT cross_channel_events_channel_check
        CHECK (channel IN ('EMAIL', 'SMS', 'AUTH', 'WEB', 'OTHER', 'CHAT', 'LOGIN'));

ALTER TABLE cross_channel_events
    ADD COLUMN IF NOT EXISTS attributes JSONB NOT NULL DEFAULT '{}'::jsonb;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'api_keys',
        'webhook_endpoints',
        'webhook_deliveries',
        'transaction_contexts'
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

-- Platform worker: claim pending webhook deliveries across tenants (SECURITY DEFINER).
CREATE OR REPLACE FUNCTION fn_claim_webhook_deliveries(p_limit INT DEFAULT 20)
RETURNS TABLE (
    delivery_id UUID,
    tenant_id UUID,
    endpoint_id UUID,
    event_type TEXT,
    payload JSONB,
    attempt_count INT,
    max_attempts INT,
    url TEXT,
    secret TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT d.id, d.tenant_id, d.endpoint_id, d.event_type, d.payload,
           d.attempt_count, d.max_attempts, e.url, e.secret
    FROM webhook_deliveries d
    JOIN webhook_endpoints e ON e.id = d.endpoint_id
    WHERE d.status = 'PENDING'
      AND d.next_attempt_at <= now()
      AND e.status = 'ACTIVE'
    ORDER BY d.next_attempt_at
    LIMIT GREATEST(1, LEAST(COALESCE(p_limit, 20), 50));
END;
$$;

GRANT EXECUTE ON FUNCTION fn_claim_webhook_deliveries(INT) TO sv_app;

CREATE OR REPLACE FUNCTION fn_resolve_api_key(p_hash TEXT)
RETURNS TABLE (
    id UUID,
    tenant_id UUID,
    name TEXT,
    prefix TEXT,
    scopes TEXT[],
    status TEXT,
    expires_at TIMESTAMPTZ,
    rate_limit_rps INT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT k.id, k.tenant_id, k.name, k.prefix, k.scopes, k.status, k.expires_at, k.rate_limit_rps
    FROM api_keys k
    WHERE k.key_hash = p_hash
    LIMIT 1;
END;
$$;

GRANT EXECUTE ON FUNCTION fn_resolve_api_key(TEXT) TO sv_app;

CREATE OR REPLACE FUNCTION fn_touch_api_key(p_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    UPDATE api_keys
    SET last_used_at = now(), usage_count = usage_count + 1
    WHERE id = p_id;
END;
$$;

GRANT EXECUTE ON FUNCTION fn_touch_api_key(UUID) TO sv_app;

