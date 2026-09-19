-- F3: Row-Level Security + SECURITY DEFINER platform functions + max_concurrent_calls

ALTER TABLE tenant_settings
    ADD COLUMN IF NOT EXISTS max_concurrent_calls INT NOT NULL DEFAULT 20
        CHECK (max_concurrent_calls > 0 AND max_concurrent_calls <= 500);

-- ---------------------------------------------------------------------------
-- RLS helpers: fail closed when app.tenant_id is unset (NULL comparisons never match)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION app_current_tenant_id()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$
    SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid;
$$;

-- Tenant-owned tables (tenant_id column)
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'users',
        'tenant_settings',
        'audit_blocks',
        'refresh_tokens',
        'login_failures',
        'login_locks'
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
    END LOOP;
END $$;

-- tenants: own row only (id = app.tenant_id), not filtered by tenant_id column
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON tenants;
CREATE POLICY tenant_isolation ON tenants
    USING (id = app_current_tenant_id())
    WITH CHECK (id = app_current_tenant_id());

-- ---------------------------------------------------------------------------
-- Platform SECURITY DEFINER functions (owned by sv_owner; run as owner → bypass RLS)
-- Documented in docs/v2/features/F3.md
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_find_tenant_by_slug(p_slug text)
RETURNS TABLE (
    id uuid,
    name text,
    slug text,
    industry text,
    status text,
    region text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT t.id, t.name, t.slug, t.industry, t.status, t.region
    FROM tenants t
    WHERE lower(t.slug) = lower(trim(p_slug));
END;
$$;

CREATE OR REPLACE FUNCTION fn_find_user_for_login(p_slug text, p_email text)
RETURNS TABLE (
    tenant_id uuid,
    tenant_slug text,
    tenant_status text,
    user_id uuid,
    email text,
    password_hash text,
    display_name text,
    role text,
    status text,
    mfa_enabled boolean,
    mfa_secret text,
    token_version int
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT
        t.id,
        t.slug,
        t.status,
        u.id,
        u.email,
        u.password_hash,
        u.display_name,
        u.role,
        u.status,
        u.mfa_enabled,
        u.mfa_secret,
        u.token_version
    FROM tenants t
    JOIN users u ON u.tenant_id = t.id
    WHERE lower(t.slug) = lower(trim(p_slug))
      AND lower(u.email) = lower(trim(p_email));
END;
$$;

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

    tenant_id := p_tenant_id;
    slug := p_slug;
    user_id := p_user_id;
    RETURN NEXT;
END;
$$;

CREATE OR REPLACE FUNCTION fn_slug_exists(p_slug text)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN EXISTS (SELECT 1 FROM tenants t WHERE lower(t.slug) = lower(trim(p_slug)));
END;
$$;

REVOKE ALL ON FUNCTION fn_find_tenant_by_slug(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_find_user_for_login(text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_register_tenant(uuid, text, text, text, text, uuid, text, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_slug_exists(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_current_tenant_id() FROM PUBLIC;

GRANT EXECUTE ON FUNCTION fn_find_tenant_by_slug(text) TO sv_app;
GRANT EXECUTE ON FUNCTION fn_find_user_for_login(text, text) TO sv_app;
GRANT EXECUTE ON FUNCTION fn_register_tenant(uuid, text, text, text, text, uuid, text, text, text) TO sv_app;
GRANT EXECUTE ON FUNCTION fn_slug_exists(text) TO sv_app;
GRANT EXECUTE ON FUNCTION app_current_tenant_id() TO sv_app;

CREATE OR REPLACE FUNCTION fn_find_refresh_token(p_hash text)
RETURNS TABLE (
    id uuid,
    tenant_id uuid,
    user_id uuid,
    token_hash char,
    expires_at timestamptz,
    revoked_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT r.id, r.tenant_id, r.user_id, r.token_hash, r.expires_at, r.revoked_at
    FROM refresh_tokens r
    WHERE r.token_hash = p_hash
      AND r.revoked_at IS NULL;
END;
$$;

REVOKE ALL ON FUNCTION fn_find_refresh_token(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_find_refresh_token(text) TO sv_app;

-- NOTE: sv_owner needs BYPASSRLS so FORCE RLS does not block Flyway.
-- That attribute is set in infra/postgres/init/01-roles.sql (bootstrap superuser).
-- On an existing volume, run once as sv_bootstrap:
--   ALTER ROLE sv_owner BYPASSRLS;

