-- F10: AGI hangup must finalize call_sessions even when the request has no JWT
-- tenant context (service-token only). SECURITY DEFINER bypasses RLS for the
-- UUID-keyed update; app tables remain FORCE RLS for normal sv_app queries.

CREATE OR REPLACE FUNCTION fn_telephony_session_tenant(p_sv_session uuid)
RETURNS uuid
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
STABLE
AS $$
    SELECT tenant_id FROM call_sessions WHERE sv_session_uuid = p_sv_session LIMIT 1;
$$;

CREATE OR REPLACE FUNCTION fn_telephony_finalize_session(
    p_sv_session uuid,
    p_peak_score double precision,
    p_peak_level text,
    p_outcome text
)
RETURNS TABLE (
    finalized boolean,
    tenant_id uuid,
    caller_employee_id uuid,
    callee_employee_id uuid
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_tenant uuid;
    v_caller uuid;
    v_callee uuid;
    v_n int;
BEGIN
    SELECT cs.tenant_id, cs.caller_employee_id, cs.callee_employee_id
      INTO v_tenant, v_caller, v_callee
    FROM call_sessions cs
    WHERE cs.sv_session_uuid = p_sv_session;

    IF v_tenant IS NULL THEN
        finalized := false;
        tenant_id := NULL;
        caller_employee_id := NULL;
        callee_employee_id := NULL;
        RETURN NEXT;
        RETURN;
    END IF;

    UPDATE call_sessions cs
    SET ended_at = now(),
        peak_score = p_peak_score,
        peak_level = p_peak_level,
        final_outcome = COALESCE(NULLIF(trim(p_outcome), ''), 'ENDED')
    WHERE cs.sv_session_uuid = p_sv_session
      AND cs.ended_at IS NULL;

    GET DIAGNOSTICS v_n = ROW_COUNT;

    finalized := v_n > 0;
    tenant_id := v_tenant;
    caller_employee_id := v_caller;
    callee_employee_id := v_callee;
    RETURN NEXT;
END;
$$;

REVOKE ALL ON FUNCTION fn_telephony_session_tenant(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION fn_telephony_finalize_session(uuid, double precision, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_telephony_session_tenant(uuid) TO sv_app;
GRANT EXECUTE ON FUNCTION fn_telephony_finalize_session(uuid, double precision, text, text) TO sv_app;
