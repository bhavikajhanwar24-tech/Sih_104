-- F18: SECURITY DEFINER helpers for demo seed/reset (RLS-safe).

CREATE OR REPLACE FUNCTION fn_demo_flag_tenant(p_tenant_id uuid, p_is_demo boolean DEFAULT TRUE)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    UPDATE tenants SET is_demo = COALESCE(p_is_demo, TRUE) WHERE id = p_tenant_id;
END;
$$;

CREATE OR REPLACE FUNCTION fn_demo_find_tenant(p_slug text)
RETURNS uuid
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT id FROM tenants WHERE lower(slug) = lower(p_slug) LIMIT 1;
$$;

CREATE OR REPLACE FUNCTION fn_demo_list_tenants()
RETURNS SETOF uuid
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT id FROM tenants WHERE is_demo = TRUE;
$$;

CREATE OR REPLACE FUNCTION fn_demo_insert_user(
    p_user_id uuid,
    p_tenant_id uuid,
    p_email text,
    p_password_hash text,
    p_display_name text,
    p_role text
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO users (
        id, tenant_id, email, password_hash, display_name, role,
        mfa_enabled, status, token_version, created_at, updated_at
    ) VALUES (
        p_user_id, p_tenant_id, lower(p_email), p_password_hash, p_display_name,
        p_role, FALSE, 'ACTIVE', 0, now(), now()
    )
    ON CONFLICT DO NOTHING;
END;
$$;

CREATE OR REPLACE FUNCTION fn_demo_wipe_tenant(p_tenant_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    t text;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM tenants
        WHERE id = p_tenant_id
          AND (is_demo = TRUE OR slug LIKE 'demo-%')
    ) THEN
        RAISE EXCEPTION 'not a demo tenant';
    END IF;

    UPDATE tenants SET is_demo = TRUE WHERE id = p_tenant_id;

    FOREACH t IN ARRAY ARRAY[
        'session_labels', 'session_ticks', 'session_reasons', 'session_extractions',
        'forensic_dossiers', 'session_actions', 'call_sessions',
        'consents', 'consent_tokens', 'voice_passports', 'notice_assets',
        'employee_authority', 'employee_phones', 'known_relationships',
        'sip_endpoints', 'employees', 'departments',
        'beneficiary_accounts', 'external_parties',
        'policy_rules', 'policy_document_chunks', 'policy_documents', 'policy_sets',
        'fusion_configs', 'response_plans', 'trunks',
        'refresh_tokens', 'users', 'tenant_settings'
    ]
    LOOP
        BEGIN
            EXECUTE format('DELETE FROM %I WHERE tenant_id = $1', t) USING p_tenant_id;
        EXCEPTION
            WHEN undefined_table THEN NULL;
            WHEN undefined_column THEN NULL;
        END;
    END LOOP;

    DELETE FROM tenants WHERE id = p_tenant_id AND is_demo = TRUE;
END;
$$;

REVOKE ALL ON FUNCTION fn_demo_flag_tenant(uuid, boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_demo_find_tenant(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_demo_list_tenants() FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_demo_insert_user(uuid, uuid, text, text, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_demo_wipe_tenant(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_demo_flag_tenant(uuid, boolean) TO sv_app;
GRANT EXECUTE ON FUNCTION fn_demo_find_tenant(text) TO sv_app;
GRANT EXECUTE ON FUNCTION fn_demo_list_tenants() TO sv_app;
GRANT EXECUTE ON FUNCTION fn_demo_insert_user(uuid, uuid, text, text, text, text) TO sv_app;
GRANT EXECUTE ON FUNCTION fn_demo_wipe_tenant(uuid) TO sv_app;
