-- Recreate clear-all helper (V029 may have been recorded without this body on some DBs).
CREATE OR REPLACE FUNCTION fn_telephony_clear_all_active(p_outcome text)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    n integer;
    v_outcome text := COALESCE(NULLIF(trim(p_outcome), ''), 'OPERATOR_CLEAR');
BEGIN
    UPDATE call_sessions
    SET ended_at = now(),
        final_outcome = COALESCE(final_outcome, v_outcome)
    WHERE ended_at IS NULL;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END;
$$;

REVOKE ALL ON FUNCTION fn_telephony_clear_all_active(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_telephony_clear_all_active(text) TO sv_app;
