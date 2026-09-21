-- F10: Multi-tenant telephony — trunks, SIP endpoints, call-session metadata,
-- PJSIP REALTIME schema (asterisk.*) for Asterisk res_config_pgsql.
-- Decision: app tables stay RLS-isolated; Asterisk role sv_asterisk only sees
-- schema asterisk (no tenant tables). Provisioning syncs sip_endpoints → ps_*.

-- ---------------------------------------------------------------------------
-- tenants.telephony_ordinal (extension namespace: ordinal*1000 + nnn)
-- ---------------------------------------------------------------------------

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS telephony_ordinal INT;

UPDATE tenants t
SET telephony_ordinal = sub.rn
FROM (
    SELECT id, ROW_NUMBER() OVER (ORDER BY created_at ASC, id ASC) AS rn
    FROM tenants
) sub
WHERE t.id = sub.id
  AND t.telephony_ordinal IS NULL;

ALTER TABLE tenants
    ALTER COLUMN telephony_ordinal SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenants_telephony_ordinal
    ON tenants (telephony_ordinal);

-- ---------------------------------------------------------------------------
-- trunks
-- ---------------------------------------------------------------------------

CREATE TABLE trunks (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (id),
    name                TEXT NOT NULL,
    type                TEXT NOT NULL
        CHECK (type IN ('INTERNAL', 'CARRIER', 'CCAAS')),
    cli_prefixes        TEXT[] NOT NULL DEFAULT '{}'::text[],
    provider_metadata   JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_trunks_tenant_name UNIQUE (tenant_id, name)
);

CREATE INDEX idx_trunks_tenant ON trunks (tenant_id);

-- ---------------------------------------------------------------------------
-- sip_endpoints (tenant-owned identities; passwords encrypted at rest)
-- ---------------------------------------------------------------------------

CREATE TABLE sip_endpoints (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    employee_id             UUID REFERENCES employees (id) ON DELETE SET NULL,
    extension               TEXT NOT NULL,
    username                TEXT NOT NULL,
    password_ciphertext     TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED', 'LAB_ATTACKER')),
    last_registered_at      TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sip_endpoints_tenant_extension UNIQUE (tenant_id, extension),
    CONSTRAINT uq_sip_endpoints_tenant_username UNIQUE (tenant_id, username)
);

CREATE INDEX idx_sip_endpoints_tenant_emp ON sip_endpoints (tenant_id, employee_id);
CREATE INDEX idx_sip_endpoints_extension ON sip_endpoints (extension);

-- ---------------------------------------------------------------------------
-- call_sessions — METADATA ONLY (no audio, no transcripts)
-- ---------------------------------------------------------------------------

CREATE TABLE call_sessions (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                       UUID NOT NULL REFERENCES tenants (id),
    started_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at                        TIMESTAMPTZ,
    caller_number                   TEXT,
    callee_number                   TEXT,
    caller_employee_id              UUID REFERENCES employees (id) ON DELETE SET NULL,
    callee_employee_id              UUID REFERENCES employees (id) ON DELETE SET NULL,
    direction                       TEXT NOT NULL
        CHECK (direction IN ('INBOUND', 'OUTBOUND', 'INTERNAL')),
    trunk_id                        UUID REFERENCES trunks (id) ON DELETE SET NULL,
    snapshot_policy_version         INT,
    snapshot_fusion_version         INT,
    snapshot_response_plan_version  INT,
    peak_score                      DOUBLE PRECISION,
    peak_level                      TEXT,
    final_outcome                   TEXT,
    sip_call_id                     TEXT,
    sv_session_uuid                 UUID NOT NULL,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_call_sessions_sv_session UNIQUE (sv_session_uuid)
);

CREATE INDEX idx_call_sessions_tenant_started ON call_sessions (tenant_id, started_at DESC);
CREATE INDEX idx_call_sessions_tenant_sip ON call_sessions (tenant_id, sip_call_id);

-- ---------------------------------------------------------------------------
-- Seed INTERNAL trunk per existing tenant
-- ---------------------------------------------------------------------------

INSERT INTO trunks (id, tenant_id, name, type, cli_prefixes, provider_metadata)
SELECT gen_random_uuid(), t.id, 'Internal', 'INTERNAL', '{}'::text[], '{}'::jsonb
FROM tenants t
WHERE NOT EXISTS (
    SELECT 1 FROM trunks tr WHERE tr.tenant_id = t.id AND tr.name = 'Internal'
);

-- ---------------------------------------------------------------------------
-- RLS (FORCE + tenant_isolation)
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['trunks', 'sip_endpoints', 'call_sessions']
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

-- ---------------------------------------------------------------------------
-- Schema asterisk — PJSIP REALTIME (NO RLS). Asterisk role sv_asterisk only.
-- Password note: changeme_asterisk (also .env.example ASTERISK_DB_PASSWORD).
-- ---------------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS asterisk;
REVOKE ALL ON SCHEMA asterisk FROM PUBLIC;

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sv_asterisk') THEN
        CREATE ROLE sv_asterisk LOGIN PASSWORD 'changeme_asterisk'
            NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;

-- Connect grant must use current_database() — local Docker is "sentinelvoice",
-- Supabase Session Pooler is typically "postgres".
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO sv_asterisk', current_database());
END
$$;
GRANT USAGE ON SCHEMA asterisk TO sv_asterisk;
GRANT USAGE ON SCHEMA asterisk TO sv_app;
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'sv_owner') THEN
        GRANT USAGE ON SCHEMA asterisk TO sv_owner;
    END IF;
END
$$;

-- ps_endpoints — columns Asterisk res_pjsip realtime expects
CREATE TABLE asterisk.ps_endpoints (
    id                              TEXT PRIMARY KEY,
    transport                       TEXT,
    aors                            TEXT,
    auth                            TEXT,
    context                         TEXT,
    disallow                        TEXT,
    allow                           TEXT,
    direct_media                    TEXT,
    rtp_symmetric                   TEXT,
    force_rport                     TEXT,
    rewrite_contact                 TEXT,
    ice_support                     TEXT,
    media_use_received_transport    TEXT,
    media_encryption                TEXT,
    inband_progress                 TEXT,
    media_address                   TEXT
);

-- ps_auths
CREATE TABLE asterisk.ps_auths (
    id          TEXT PRIMARY KEY,
    auth_type   TEXT,
    password    TEXT,
    username    TEXT
);

-- ps_aors
CREATE TABLE asterisk.ps_aors (
    id                  TEXT PRIMARY KEY,
    max_contacts        INTEGER,
    remove_existing     TEXT,
    qualify_frequency   INTEGER
);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA asterisk TO sv_asterisk;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA asterisk TO sv_app;
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'sv_owner') THEN
        GRANT ALL ON ALL TABLES IN SCHEMA asterisk TO sv_owner;
        EXECUTE 'ALTER DEFAULT PRIVILEGES FOR ROLE sv_owner IN SCHEMA asterisk
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sv_asterisk';
        EXECUTE 'ALTER DEFAULT PRIVILEGES FOR ROLE sv_owner IN SCHEMA asterisk
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sv_app';
    ELSE
        -- Supabase: Flyway runs as postgres (no sv_owner role)
        GRANT ALL ON ALL TABLES IN SCHEMA asterisk TO postgres;
        ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA asterisk
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sv_asterisk;
        ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA asterisk
            GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sv_app;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- Cross-tenant resolve helpers (SECURITY DEFINER — AGI / internal APIs)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_telephony_resolve_extension(p_extension text)
RETURNS TABLE (
    tenant_id       uuid,
    employee_id     uuid,
    endpoint_id     uuid,
    username        text,
    extension       text,
    status          text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        e.tenant_id,
        e.employee_id,
        e.id,
        e.username,
        e.extension,
        e.status
    FROM sip_endpoints e
    WHERE e.extension = trim(p_extension)
      AND e.status IN ('ACTIVE', 'LAB_ATTACKER')
    ORDER BY e.created_at ASC
    LIMIT 1;
END;
$$;

CREATE OR REPLACE FUNCTION fn_telephony_resolve_username(p_username text)
RETURNS TABLE (
    tenant_id       uuid,
    employee_id     uuid,
    endpoint_id     uuid,
    username        text,
    extension       text,
    status          text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        e.tenant_id,
        e.employee_id,
        e.id,
        e.username,
        e.extension,
        e.status
    FROM sip_endpoints e
    WHERE e.username = trim(p_username)
      AND e.status IN ('ACTIVE', 'LAB_ATTACKER')
    ORDER BY e.created_at ASC
    LIMIT 1;
END;
$$;

CREATE OR REPLACE FUNCTION fn_telephony_next_ordinal()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_next integer;
BEGIN
    SELECT COALESCE(MAX(telephony_ordinal), 0) + 1 INTO v_next FROM tenants;
    RETURN v_next;
END;
$$;

REVOKE ALL ON FUNCTION fn_telephony_resolve_extension(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_telephony_resolve_username(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_telephony_next_ordinal() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_telephony_resolve_extension(text) TO sv_app;
GRANT EXECUTE ON FUNCTION fn_telephony_resolve_username(text) TO sv_app;
GRANT EXECUTE ON FUNCTION fn_telephony_next_ordinal() TO sv_app;

-- ---------------------------------------------------------------------------
-- Extend tenant registration: ordinal + Internal trunk
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_register_tenant(
    p_tenant_id uuid,
    p_name text,
    p_slug text,
    p_industry text,
    p_region text,
    p_user_id uuid,
    p_admin_email text,
    p_password_hash text,
    p_admin_display_name text
)
RETURNS TABLE (tenant_id uuid, slug text, user_id uuid)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_now timestamptz := now();
    v_cfg jsonb;
    v_plan jsonb;
    v_ordinal int;
BEGIN
    IF EXISTS (SELECT 1 FROM tenants t WHERE lower(t.slug) = lower(p_slug)) THEN
        RAISE EXCEPTION 'slug already exists';
    END IF;

    SELECT COALESCE(MAX(telephony_ordinal), 0) + 1 INTO v_ordinal FROM tenants;

    INSERT INTO tenants (id, name, slug, industry, status, region, created_at, telephony_ordinal)
    VALUES (p_tenant_id, p_name, p_slug, p_industry, 'ACTIVE', p_region, v_now, v_ordinal);

    INSERT INTO tenant_settings (
        tenant_id, retention_days, allow_external_llm, llm_fail_policy,
        consent_notice_text, extras, created_at, updated_at, max_concurrent_calls
    ) VALUES (
        p_tenant_id, 90, false, 'CONTINUE_RULES_ONLY',
        'Default SentinelVoice consent notice.', '{}'::jsonb, v_now, v_now, 20
    );

    INSERT INTO users (
        id, tenant_id, email, password_hash, display_name, role,
        mfa_enabled, status, token_version, created_at, updated_at
    ) VALUES (
        p_user_id, p_tenant_id, lower(p_admin_email), p_password_hash, p_admin_display_name,
        'TENANT_ADMIN', false, 'ACTIVE', 0, v_now, v_now
    );

    SELECT config INTO v_cfg FROM platform_fusion_defaults WHERE id = 1;
    IF v_cfg IS NULL THEN
        RAISE EXCEPTION 'platform fusion default missing';
    END IF;

    INSERT INTO fusion_configs (
        id, tenant_id, version, status, config, created_by, submitted_by, approved_by,
        approved_at, content_sha256, created_at, updated_at
    ) VALUES (
        gen_random_uuid(), p_tenant_id, 1, 'ACTIVE', v_cfg, p_user_id, NULL, NULL,
        v_now, encode(digest(v_cfg::text, 'sha256'), 'hex'), v_now, v_now
    );

    SELECT plan INTO v_plan FROM platform_response_defaults WHERE id = 1;
    IF v_plan IS NULL THEN
        RAISE EXCEPTION 'platform response default missing';
    END IF;

    INSERT INTO response_plans (
        id, tenant_id, version, status, plan, created_by, submitted_by, approved_by,
        approved_at, content_sha256, created_at, updated_at
    ) VALUES (
        gen_random_uuid(), p_tenant_id, 1, 'ACTIVE', v_plan, p_user_id, NULL, NULL,
        v_now, encode(digest(v_plan::text, 'sha256'), 'hex'), v_now, v_now
    );

    INSERT INTO trunks (id, tenant_id, name, type, cli_prefixes, provider_metadata)
    VALUES (gen_random_uuid(), p_tenant_id, 'Internal', 'INTERNAL', '{}'::text[], '{}'::jsonb);

    tenant_id := p_tenant_id;
    slug := p_slug;
    user_id := p_user_id;
    RETURN NEXT;
END;
$$;
