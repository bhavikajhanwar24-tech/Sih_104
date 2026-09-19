-- F1 baseline: tenants, settings, users, immutable audit_blocks.
-- Conventions: docs/v2/DB_CONVENTIONS.md

CREATE TABLE tenants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    slug        TEXT NOT NULL,
    industry    TEXT,
    status      TEXT NOT NULL,
    region      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tenants_slug_unique UNIQUE (slug),
    CONSTRAINT tenants_status_check CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED'))
);

CREATE TABLE tenant_settings (
    tenant_id            UUID PRIMARY KEY REFERENCES tenants (id),
    retention_days       INT NOT NULL DEFAULT 90,
    allow_external_llm   BOOLEAN NOT NULL DEFAULT FALSE,
    llm_fail_policy      TEXT NOT NULL DEFAULT 'CONTINUE_RULES_ONLY',
    consent_notice_text  TEXT,
    extras               JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tenant_settings_llm_fail_policy_check
        CHECK (llm_fail_policy IN ('CONTINUE_RULES_ONLY', 'RAISE_ONE_LEVEL')),
    CONSTRAINT tenant_settings_retention_days_check CHECK (retention_days > 0)
);

CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants (id),
    email          TEXT NOT NULL,
    password_hash  TEXT NOT NULL,
    display_name   TEXT NOT NULL,
    role           TEXT NOT NULL,
    mfa_secret     TEXT,
    status         TEXT NOT NULL DEFAULT 'ACTIVE',
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_role_check CHECK (role IN (
        'TENANT_ADMIN', 'POLICY_APPROVER', 'ANALYST', 'AUDITOR'
    )),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'DISABLED', 'PENDING'))
);

CREATE UNIQUE INDEX users_tenant_email_lower_uidx ON users (tenant_id, lower(email));
CREATE INDEX users_tenant_id_idx ON users (tenant_id);

CREATE TABLE audit_blocks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants (id),
    seq         BIGINT NOT NULL,
    prev_hash   CHAR(64) NOT NULL,
    hash        CHAR(64) NOT NULL,
    event_type  TEXT NOT NULL,
    actor_type  TEXT NOT NULL,
    actor_id    TEXT,
    payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT audit_blocks_tenant_seq_unique UNIQUE (tenant_id, seq),
    CONSTRAINT audit_blocks_actor_type_check CHECK (actor_type IN ('USER', 'SYSTEM', 'API_KEY')),
    CONSTRAINT audit_blocks_prev_hash_len CHECK (char_length(prev_hash) = 64),
    CONSTRAINT audit_blocks_hash_len CHECK (char_length(hash) = 64)
);

CREATE INDEX audit_blocks_tenant_created_idx ON audit_blocks (tenant_id, created_at);
CREATE INDEX audit_blocks_tenant_event_idx ON audit_blocks (tenant_id, event_type);

-- Immutability trigger: no UPDATE or DELETE on audit_blocks
CREATE OR REPLACE FUNCTION audit_blocks_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_blocks are immutable (attempted %)', TG_OP;
END;
$$;

CREATE TRIGGER audit_blocks_no_update
    BEFORE UPDATE ON audit_blocks
    FOR EACH ROW
    EXECUTE FUNCTION audit_blocks_immutability();

CREATE TRIGGER audit_blocks_no_delete
    BEFORE DELETE ON audit_blocks
    FOR EACH ROW
    EXECUTE FUNCTION audit_blocks_immutability();

-- Privileges for sv_app (runtime). Flyway runs as sv_owner.
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE tenants, tenant_settings, users TO sv_app;
GRANT SELECT, INSERT ON TABLE audit_blocks TO sv_app;
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE audit_blocks FROM sv_app;
