-- F15 follow-up: public consent token lookup + active tenant list for purge job
-- (V025 was already applied; these SECURITY DEFINER helpers were added after first apply.)

CREATE OR REPLACE FUNCTION fn_find_consent_token(p_hash text)
RETURNS TABLE (
    id uuid,
    tenant_id uuid,
    employee_id uuid,
    purpose text,
    expires_at timestamptz,
    consumed_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT t.id, t.tenant_id, t.employee_id, t.purpose, t.expires_at, t.consumed_at
    FROM consent_tokens t
    WHERE t.token_hash = p_hash
      AND t.consumed_at IS NULL
      AND t.expires_at > now();
END;
$$;

REVOKE ALL ON FUNCTION fn_find_consent_token(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_find_consent_token(text) TO sv_app;

CREATE OR REPLACE FUNCTION fn_list_active_tenant_ids()
RETURNS TABLE (id uuid)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT t.id
    FROM tenants t
    WHERE t.status = 'ACTIVE';
END;
$$;

REVOKE ALL ON FUNCTION fn_list_active_tenant_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_list_active_tenant_ids() TO sv_app;
