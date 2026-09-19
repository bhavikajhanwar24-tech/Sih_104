-- F2: auth tokens, login lockout, user token version / MFA flags

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS token_version INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    user_id         UUID NOT NULL REFERENCES users (id),
    token_hash      CHAR(64) NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by     UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT refresh_tokens_hash_unique UNIQUE (token_hash)
);

CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_tenant_idx ON refresh_tokens (tenant_id);

CREATE TABLE login_failures (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    email_lower     TEXT NOT NULL,
    ip_address      TEXT NOT NULL,
    failed_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX login_failures_lookup_idx
    ON login_failures (tenant_id, email_lower, ip_address, failed_at DESC);

CREATE TABLE login_locks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    email_lower     TEXT NOT NULL,
    ip_address      TEXT NOT NULL,
    locked_until    TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT login_locks_unique UNIQUE (tenant_id, email_lower, ip_address)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE refresh_tokens, login_failures, login_locks TO sv_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE users, tenants, tenant_settings TO sv_app;
