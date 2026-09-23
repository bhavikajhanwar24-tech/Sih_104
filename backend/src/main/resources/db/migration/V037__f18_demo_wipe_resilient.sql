-- F18: resilient demo wipe — free slug even when FK leftovers remain.

CREATE OR REPLACE FUNCTION fn_demo_wipe_tenant(p_tenant_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    t text;
    v_slug text;
BEGIN
    SELECT slug INTO v_slug FROM tenants WHERE id = p_tenant_id;
    IF v_slug IS NULL THEN
        RETURN;
    END IF;
    IF NOT (v_slug LIKE 'demo-%' OR EXISTS (
        SELECT 1 FROM tenants WHERE id = p_tenant_id AND is_demo = TRUE
    )) THEN
        RAISE EXCEPTION 'not a demo tenant';
    END IF;

    UPDATE tenants SET is_demo = TRUE WHERE id = p_tenant_id;

    BEGIN
        UPDATE policy_sets SET compilation_id = NULL WHERE tenant_id = p_tenant_id;
    EXCEPTION WHEN OTHERS THEN NULL;
    END;

    BEGIN
        UPDATE api_keys SET created_by = NULL WHERE tenant_id = p_tenant_id;
    EXCEPTION WHEN OTHERS THEN NULL;
    END;

    FOREACH t IN ARRAY ARRAY[
        'session_labels', 'session_ticks', 'session_reasons', 'session_extractions',
        'forensic_dossiers', 'session_actions', 'call_sessions',
        'webhook_deliveries', 'api_keys', 'integration_credentials',
        'consents', 'consent_tokens', 'voice_passports', 'notice_assets',
        'employee_authority', 'employee_phones', 'known_relationships',
        'sip_endpoints', 'employees', 'departments',
        'beneficiary_accounts', 'external_parties',
        'policy_compile_chunk_results', 'policy_compilation_chunks', 'policy_compilations',
        'policy_rules', 'policy_document_chunks', 'policy_documents', 'policy_sets',
        'fusion_configs', 'response_plans', 'trunks',
        'refresh_tokens', 'login_failures', 'login_locks',
        'users', 'tenant_settings'
    ]
    LOOP
        BEGIN
            EXECUTE format('DELETE FROM %I WHERE tenant_id = $1', t) USING p_tenant_id;
        EXCEPTION
            WHEN undefined_table THEN NULL;
            WHEN undefined_column THEN NULL;
            WHEN foreign_key_violation THEN NULL;
            WHEN OTHERS THEN NULL;
        END;
    END LOOP;

    BEGIN
        DELETE FROM tenants WHERE id = p_tenant_id;
    EXCEPTION WHEN OTHERS THEN
        -- Free the slug so a fresh demo seed can proceed
        UPDATE tenants
        SET slug = 'purged-' || replace(id::text, '-', ''),
            name = coalesce(name, 'purged') || ' (purged)',
            status = 'SUSPENDED',
            is_demo = TRUE
        WHERE id = p_tenant_id;
    END;
END;
$$;
