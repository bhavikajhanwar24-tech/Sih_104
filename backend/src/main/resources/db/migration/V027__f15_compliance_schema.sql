-- F15 schema (idempotent). Prior V025 on this DB was a different migration (checksum drift);
-- ensure consent / passport / notice columns and tables exist.

ALTER TABLE tenant_settings
    DROP CONSTRAINT IF EXISTS tenant_settings_retention_days_check;

ALTER TABLE tenant_settings
    ADD CONSTRAINT tenant_settings_retention_days_check
        CHECK (retention_days >= 7 AND retention_days <= 365);

UPDATE tenant_settings
SET retention_days = LEAST(365, GREATEST(7, retention_days))
WHERE retention_days < 7 OR retention_days > 365;

ALTER TABLE tenant_settings
    ADD COLUMN IF NOT EXISTS play_call_notice BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS notice_asset_id UUID,
    ADD COLUMN IF NOT EXISTS notice_version INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS consent_languages TEXT NOT NULL DEFAULT 'en,hi',
    ADD COLUMN IF NOT EXISTS last_retention_purge_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_retention_purge_stats JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE TABLE IF NOT EXISTS notice_assets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    kind            TEXT NOT NULL DEFAULT 'CALL_START'
        CHECK (kind IN ('CALL_START', 'TTS_TEXT')),
    filename        TEXT NOT NULL,
    content_type    TEXT NOT NULL DEFAULT 'audio/wav',
    storage_path    TEXT NOT NULL,
    byte_size       INT NOT NULL DEFAULT 0,
    sha256          TEXT NOT NULL,
    tts_text        TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID
);

CREATE INDEX IF NOT EXISTS idx_notice_assets_tenant ON notice_assets (tenant_id, created_at DESC);

ALTER TABLE tenant_settings
    DROP CONSTRAINT IF EXISTS tenant_settings_notice_asset_fk;
ALTER TABLE tenant_settings
    ADD CONSTRAINT tenant_settings_notice_asset_fk
        FOREIGN KEY (notice_asset_id) REFERENCES notice_assets (id) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS consents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    employee_id     UUID NOT NULL REFERENCES employees (id),
    purpose         TEXT NOT NULL
        CHECK (purpose IN ('MONITORING', 'VOICE_PASSPORT')),
    status          TEXT NOT NULL
        CHECK (status IN ('GRANTED', 'WITHDRAWN', 'NOT_REQUIRED')),
    method          TEXT NOT NULL DEFAULT 'ADMIN'
        CHECK (method IN ('ADMIN', 'BULK', 'PUBLIC_LINK', 'IMPORT')),
    notice_version  INT NOT NULL DEFAULT 1,
    evidence_ref    TEXT,
    granted_at      TIMESTAMPTZ,
    withdrawn_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, employee_id, purpose)
);

CREATE INDEX IF NOT EXISTS idx_consents_tenant_status ON consents (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_consents_tenant_purpose ON consents (tenant_id, purpose);

CREATE TABLE IF NOT EXISTS consent_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    employee_id     UUID NOT NULL REFERENCES employees (id),
    purpose         TEXT NOT NULL
        CHECK (purpose IN ('MONITORING', 'VOICE_PASSPORT')),
    token_hash      TEXT NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID
);

CREATE INDEX IF NOT EXISTS idx_consent_tokens_tenant ON consent_tokens (tenant_id, expires_at);

CREATE TABLE IF NOT EXISTS voice_passports (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    employee_id     UUID NOT NULL REFERENCES employees (id),
    embedding_cipher BYTEA NOT NULL,
    model_version   TEXT NOT NULL,
    dim             INT NOT NULL DEFAULT 192,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, employee_id)
);

CREATE INDEX IF NOT EXISTS idx_voice_passports_tenant ON voice_passports (tenant_id);

ALTER TABLE notice_assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE notice_assets FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS notice_assets_tenant_isolation ON notice_assets;
CREATE POLICY notice_assets_tenant_isolation ON notice_assets
    USING (tenant_id = app_current_tenant_id())
    WITH CHECK (tenant_id = app_current_tenant_id());

ALTER TABLE consents ENABLE ROW LEVEL SECURITY;
ALTER TABLE consents FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS consents_tenant_isolation ON consents;
CREATE POLICY consents_tenant_isolation ON consents
    USING (tenant_id = app_current_tenant_id())
    WITH CHECK (tenant_id = app_current_tenant_id());

ALTER TABLE consent_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE consent_tokens FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS consent_tokens_tenant_isolation ON consent_tokens;
CREATE POLICY consent_tokens_tenant_isolation ON consent_tokens
    USING (tenant_id = app_current_tenant_id())
    WITH CHECK (tenant_id = app_current_tenant_id());

ALTER TABLE voice_passports ENABLE ROW LEVEL SECURITY;
ALTER TABLE voice_passports FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS voice_passports_tenant_isolation ON voice_passports;
CREATE POLICY voice_passports_tenant_isolation ON voice_passports
    USING (tenant_id = app_current_tenant_id())
    WITH CHECK (tenant_id = app_current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON notice_assets TO sv_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON consents TO sv_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON consent_tokens TO sv_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON voice_passports TO sv_app;
