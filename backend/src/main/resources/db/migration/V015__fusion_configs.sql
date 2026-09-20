-- F8: Per-tenant fusion configuration (versioned, dual-control approval).
-- Platform default is seeded once; every existing / new tenant gets an ACTIVE copy.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE fusion_configs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    version         INT NOT NULL,
    status          TEXT NOT NULL,
    config          JSONB NOT NULL,
    created_by      UUID REFERENCES users (id),
    submitted_by    UUID REFERENCES users (id),
    approved_by     UUID REFERENCES users (id),
    approved_at     TIMESTAMPTZ,
    reject_comment  TEXT,
    content_sha256  CHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fusion_configs_status_check CHECK (status IN (
        'DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'SUPERSEDED', 'REJECTED'
    )),
    CONSTRAINT fusion_configs_version_positive CHECK (version > 0),
    CONSTRAINT fusion_configs_sha_len CHECK (char_length(content_sha256) = 64),
    CONSTRAINT fusion_configs_tenant_version_unique UNIQUE (tenant_id, version)
);

CREATE UNIQUE INDEX uq_fusion_configs_one_active
    ON fusion_configs (tenant_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_fusion_configs_tenant_status
    ON fusion_configs (tenant_id, status);

CREATE INDEX idx_fusion_configs_tenant_created
    ON fusion_configs (tenant_id, created_at DESC);

ALTER TABLE fusion_configs ENABLE ROW LEVEL SECURITY;
ALTER TABLE fusion_configs FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fusion_configs;
CREATE POLICY tenant_isolation ON fusion_configs
    USING (tenant_id = app_current_tenant_id())
    WITH CHECK (tenant_id = app_current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON fusion_configs TO sv_app;

-- Approver must differ from submitter (same dual-control as policy sets).
CREATE OR REPLACE FUNCTION fn_fusion_config_approver_ne_submitter()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'ACTIVE'
       AND NEW.submitted_by IS NOT NULL
       AND NEW.approved_by IS NOT NULL
       AND NEW.submitted_by = NEW.approved_by THEN
        RAISE EXCEPTION 'fusion config approver must differ from submitter';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_fusion_config_approver_ne_submitter
    BEFORE UPDATE OF status ON fusion_configs
    FOR EACH ROW
    EXECUTE FUNCTION fn_fusion_config_approver_ne_submitter();

-- ---------------------------------------------------------------------------
-- Platform default JSON (values from former sentinelvoice.fusion / intervention YAML)
-- ---------------------------------------------------------------------------
CREATE TABLE platform_fusion_defaults (
    id          SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    config      JSONB NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO platform_fusion_defaults (id, config) VALUES (1, '{
  "weights": {
    "wideband": {
      "voice": 0.24, "channel": 0.08, "prosody": 0.13,
      "linguistic": 0.25, "transaction": 0.18, "relationship": 0.12
    },
    "narrowband": {
      "voice": 0.15, "channel": 0.10, "prosody": 0.12,
      "linguistic": 0.29, "transaction": 0.20, "relationship": 0.14
    }
  },
  "smoothing": {
    "lambdaUp": 0.55,
    "lambdaDown": 0.88,
    "linguisticStalenessTauMs": 3000
  },
  "familyThresholds": {
    "voice": 0.60, "channel": 0.55, "prosody": 0.60,
    "linguistic": 0.65, "transaction": 0.60, "relationship": 0.60
  },
  "corroboration": {
    "minIndependentFamiliesForL3": 2,
    "minForL4": 3
  },
  "levels": {
    "L1": {"enter": 0.30, "exit": 0.25, "minDwellMs": 1000},
    "L2": {"enter": 0.35, "exit": 0.28, "minDwellMs": 1000},
    "L3": {"enter": 0.55, "exit": 0.46, "minDwellMs": 1000},
    "L4": {"enter": 0.73, "exit": 0.66, "minDwellMs": 1000}
  },
  "insufficientEvidence": {"minSpeechMs": 3000},
  "missingEvidence": {
    "llmUnavailable": "CONTINUE_RULES_ONLY",
    "directoryEmpty": "CONTINUE_RULES_ONLY"
  },
  "hardFloors": {"acousticAloneMaxLevel": 2},
  "emergency": {
    "enabled": true,
    "rules": [
      {
        "id": "speaker_mismatch_secrecy_authority_txn",
        "targetLevel": 3,
        "cosineMismatchMin": 0.50,
        "secrecyMin": 0.85,
        "authorityMin": 0.85,
        "transactionScoreMin": 0.80
      }
    ]
  },
  "overridePinDurationMs": 120000
}'::jsonb);

-- Seed ACTIVE v1 for every existing tenant from the platform default.
INSERT INTO fusion_configs (
    id, tenant_id, version, status, config, created_by, submitted_by, approved_by,
    approved_at, content_sha256, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    t.id,
    1,
    'ACTIVE',
    d.config,
    NULL,
    NULL,
    NULL,
    now(),
    encode(digest(d.config::text, 'sha256'), 'hex'),
    now(),
    now()
FROM tenants t
CROSS JOIN platform_fusion_defaults d
WHERE d.id = 1
  AND NOT EXISTS (
      SELECT 1 FROM fusion_configs fc WHERE fc.tenant_id = t.id AND fc.status = 'ACTIVE'
  );

-- Extend tenant registration to copy platform default as ACTIVE fusion config.
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
BEGIN
    IF EXISTS (SELECT 1 FROM tenants t WHERE lower(t.slug) = lower(p_slug)) THEN
        RAISE EXCEPTION 'slug already exists';
    END IF;

    INSERT INTO tenants (id, name, slug, industry, status, region, created_at)
    VALUES (p_tenant_id, p_name, p_slug, p_industry, 'ACTIVE', p_region, v_now);

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

    tenant_id := p_tenant_id;
    slug := p_slug;
    user_id := p_user_id;
    RETURN NEXT;
END;
$$;
