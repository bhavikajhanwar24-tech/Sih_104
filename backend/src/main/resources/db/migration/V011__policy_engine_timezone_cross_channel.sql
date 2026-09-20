-- F7: tenant timezone for time.* facts; cross_channel_events for fact producers
-- (full SIEM ingest API remains F17; table is tenant-scoped and RLS-ready).

ALTER TABLE tenant_settings
    ADD COLUMN IF NOT EXISTS timezone TEXT NOT NULL DEFAULT 'Asia/Kolkata';

ALTER TABLE tenant_settings
    DROP CONSTRAINT IF EXISTS tenant_settings_timezone_check;

ALTER TABLE tenant_settings
    ADD CONSTRAINT tenant_settings_timezone_check
        CHECK (char_length(timezone) BETWEEN 1 AND 64);

CREATE TABLE IF NOT EXISTS cross_channel_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (id),
    channel             TEXT NOT NULL,
    target_employee_id  TEXT,
    target_identity_key TEXT,
    occurred_at         TIMESTAMPTZ NOT NULL,
    severity            TEXT NOT NULL DEFAULT 'LOW',
    indicator           TEXT NOT NULL,
    campaign_id         TEXT,
    description         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cross_channel_events_channel_check
        CHECK (channel IN ('EMAIL', 'SMS', 'AUTH', 'WEB', 'OTHER')),
    CONSTRAINT cross_channel_events_severity_check
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

CREATE INDEX IF NOT EXISTS idx_cross_channel_events_tenant_occurred
    ON cross_channel_events (tenant_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_cross_channel_events_tenant_target
    ON cross_channel_events (tenant_id, target_employee_id, occurred_at DESC);

DO $$
DECLARE
    t text := 'cross_channel_events';
BEGIN
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
END $$;
