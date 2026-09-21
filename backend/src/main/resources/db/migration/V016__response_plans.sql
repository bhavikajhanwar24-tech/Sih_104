-- F9: Admin-defined response plans, session action log, tenant integrations.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- response_plans (dual-control, one ACTIVE per tenant)
-- ---------------------------------------------------------------------------
CREATE TABLE response_plans (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    version         INT NOT NULL,
    status          TEXT NOT NULL,
    plan            JSONB NOT NULL,
    created_by      UUID REFERENCES users (id),
    submitted_by    UUID REFERENCES users (id),
    approved_by     UUID REFERENCES users (id),
    approved_at     TIMESTAMPTZ,
    reject_comment  TEXT,
    content_sha256  CHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT response_plans_status_check CHECK (status IN (
        'DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'SUPERSEDED', 'REJECTED'
    )),
    CONSTRAINT response_plans_version_positive CHECK (version > 0),
    CONSTRAINT response_plans_sha_len CHECK (char_length(content_sha256) = 64),
    CONSTRAINT response_plans_tenant_version_unique UNIQUE (tenant_id, version)
);

CREATE UNIQUE INDEX uq_response_plans_one_active
    ON response_plans (tenant_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_response_plans_tenant_status
    ON response_plans (tenant_id, status);

CREATE INDEX idx_response_plans_tenant_created
    ON response_plans (tenant_id, created_at DESC);

ALTER TABLE response_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE response_plans FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON response_plans;
CREATE POLICY tenant_isolation ON response_plans
    USING (tenant_id = app_current_tenant_id())
    WITH CHECK (tenant_id = app_current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON response_plans TO sv_app;

CREATE OR REPLACE FUNCTION fn_response_plan_approver_ne_submitter()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'ACTIVE'
       AND NEW.submitted_by IS NOT NULL
       AND NEW.approved_by IS NOT NULL
       AND NEW.submitted_by = NEW.approved_by THEN
        RAISE EXCEPTION 'response plan approver must differ from submitter';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_response_plan_approver_ne_submitter
    BEFORE UPDATE OF status ON response_plans
    FOR EACH ROW
    EXECUTE FUNCTION fn_response_plan_approver_ne_submitter();

-- ---------------------------------------------------------------------------
-- session_actions (PlanRunner execution log)
-- ---------------------------------------------------------------------------
CREATE TABLE session_actions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    session_id      TEXT NOT NULL,
    level           TEXT NOT NULL,
    step_index      INT NOT NULL DEFAULT 0,
    action          TEXT NOT NULL,
    status          TEXT NOT NULL,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    result          JSONB NOT NULL DEFAULT '{}'::jsonb,
    entry_token     TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT session_actions_status_check CHECK (status IN (
        'PENDING', 'EXECUTED', 'FAILED', 'SKIPPED',
        'AWAITING_OPERATOR', 'ACKED', 'OVERRIDDEN'
    )),
    CONSTRAINT session_actions_idempotent UNIQUE (tenant_id, session_id, level, step_index, entry_token)
);

CREATE INDEX idx_session_actions_session
    ON session_actions (tenant_id, session_id, created_at DESC);

ALTER TABLE session_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE session_actions FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON session_actions;
CREATE POLICY tenant_isolation ON session_actions
    USING (tenant_id = app_current_tenant_id())
    WITH CHECK (tenant_id = app_current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON session_actions TO sv_app;

-- ---------------------------------------------------------------------------
-- tenant_integrations (secrets encrypted at rest)
-- ---------------------------------------------------------------------------
CREATE TABLE tenant_integrations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    kind            TEXT NOT NULL,
    config          JSONB NOT NULL DEFAULT '{}'::jsonb,
    secrets_enc     BYTEA,
    enabled         BOOLEAN NOT NULL DEFAULT false,
    last_test_at    TIMESTAMPTZ,
    last_test_ok    BOOLEAN,
    last_test_detail TEXT,
    updated_by      UUID REFERENCES users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tenant_integrations_kind_check CHECK (kind IN (
        'SMS_NOTIFICATION',
        'SUPERVISOR_NOTIFY',
        'SUPERVISOR_BRIDGE',
        'CORE_BANKING',
        'ITSM',
        'CUSTOM_WEBHOOK'
    )),
    CONSTRAINT tenant_integrations_tenant_kind_unique UNIQUE (tenant_id, kind)
);

ALTER TABLE tenant_integrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_integrations FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON tenant_integrations;
CREATE POLICY tenant_isolation ON tenant_integrations
    USING (tenant_id = app_current_tenant_id())
    WITH CHECK (tenant_id = app_current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_integrations TO sv_app;

-- ---------------------------------------------------------------------------
-- Platform default response plan
-- ---------------------------------------------------------------------------
CREATE TABLE platform_response_defaults (
    id          SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    plan        JSONB NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO platform_response_defaults (id, plan) VALUES (1, '{
  "levels": {
    "L1": {
      "operatorOverridePermitted": true,
      "overrideRequiresSupervisor": false,
      "steps": [
        {
          "action": "LOG_ONLY",
          "params": {},
          "trigger": "ON_ENTER",
          "delayMs": 0,
          "requiresAck": false,
          "autoExecute": true,
          "operatorConfirm": false
        }
      ]
    },
    "L2": {
      "operatorOverridePermitted": true,
      "overrideRequiresSupervisor": false,
      "steps": [
        {
          "action": "OPERATOR_ADVISORY",
          "params": {
            "text": "Elevated risk — verify caller identity before proceeding.",
            "verificationSteps": [
              "Confirm customer identity against directory",
              "Ask an out-of-band verification question"
            ]
          },
          "trigger": "ON_ENTER",
          "delayMs": 0,
          "requiresAck": false,
          "autoExecute": true,
          "operatorConfirm": false
        },
        {
          "action": "WHISPER_WARNING",
          "params": {"soundId": "whisper-caution"},
          "trigger": "ON_ENTER",
          "delayMs": 500,
          "requiresAck": false,
          "autoExecute": true,
          "operatorConfirm": false
        }
      ]
    },
    "L3": {
      "operatorOverridePermitted": true,
      "overrideRequiresSupervisor": true,
      "steps": [
        {
          "action": "LOCK_APPROVAL",
          "params": {},
          "trigger": "ON_ENTER",
          "delayMs": 0,
          "requiresAck": false,
          "autoExecute": true,
          "operatorConfirm": false
        },
        {
          "action": "REQUIRE_CALLBACK_VERIFICATION",
          "params": {
            "checklist": [
              "Hang up the current call",
              "Call the customer back on the directory number",
              "Confirm the transaction request independently"
            ]
          },
          "trigger": "ON_ENTER",
          "delayMs": 0,
          "requiresAck": true,
          "autoExecute": true,
          "operatorConfirm": false
        },
        {
          "action": "SEND_OOB_MFA",
          "params": {},
          "trigger": "ON_ENTER",
          "delayMs": 0,
          "requiresAck": false,
          "autoExecute": true,
          "operatorConfirm": false
        }
      ]
    },
    "L4": {
      "operatorOverridePermitted": false,
      "overrideRequiresSupervisor": true,
      "steps": [
        {
          "action": "NOTIFY_SUPERVISOR",
          "params": {"channels": ["in_app"]},
          "trigger": "ON_ENTER",
          "delayMs": 0,
          "requiresAck": false,
          "autoExecute": true,
          "operatorConfirm": false
        },
        {
          "action": "HOLD_CALL",
          "params": {},
          "trigger": "ON_ENTER",
          "delayMs": 0,
          "requiresAck": false,
          "autoExecute": true,
          "operatorConfirm": false
        },
        {
          "action": "BRIDGE_SUPERVISOR",
          "params": {"mode": "whisper"},
          "trigger": "ON_ENTER",
          "delayMs": 1000,
          "requiresAck": false,
          "autoExecute": true,
          "operatorConfirm": false
        }
      ]
    }
  }
}'::jsonb);

INSERT INTO response_plans (
    id, tenant_id, version, status, plan, created_by, submitted_by, approved_by,
    approved_at, content_sha256, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    t.id,
    1,
    'ACTIVE',
    d.plan,
    NULL,
    NULL,
    NULL,
    now(),
    encode(digest(d.plan::text, 'sha256'), 'hex'),
    now(),
    now()
FROM tenants t
CROSS JOIN platform_response_defaults d
WHERE d.id = 1
  AND NOT EXISTS (
      SELECT 1 FROM response_plans rp WHERE rp.tenant_id = t.id AND rp.status = 'ACTIVE'
  );

-- Extend tenant registration to also seed ACTIVE response plan + empty integrations.
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

    tenant_id := p_tenant_id;
    slug := p_slug;
    user_id := p_user_id;
    RETURN NEXT;
END;
$$;
