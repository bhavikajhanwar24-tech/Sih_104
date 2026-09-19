-- Bootstrap tenant for pre-F2 single-tenant runtime. Real multi-tenancy arrives in F2/F3.
-- Fixed UUID so the Decision Plane can resolve it without a directory lookup.

INSERT INTO tenants (id, name, slug, industry, status, region, created_at)
VALUES (
    '00000000-0000-4000-8000-0000000000b1',
    'Bootstrap Tenant',
    'bootstrap',
    'LAB',
    'ACTIVE',
    'local',
    now()
);

INSERT INTO tenant_settings (
    tenant_id,
    retention_days,
    allow_external_llm,
    llm_fail_policy,
    consent_notice_text,
    extras,
    created_at,
    updated_at
) VALUES (
    '00000000-0000-4000-8000-0000000000b1',
    90,
    FALSE,
    'CONTINUE_RULES_ONLY',
    'SentinelVoice lab consent notice (bootstrap).',
    '{}'::jsonb,
    now(),
    now()
);
