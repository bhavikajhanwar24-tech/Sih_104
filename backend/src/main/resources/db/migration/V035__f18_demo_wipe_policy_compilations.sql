-- F18: wipe must clear policy_compilations before policy_sets.

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
        'policy_compile_chunk_results', 'policy_compilation_chunks', 'policy_compilations',
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

    DELETE FROM tenants WHERE id = p_tenant_id AND (is_demo = TRUE OR slug LIKE 'demo-%');
END;
$$;
