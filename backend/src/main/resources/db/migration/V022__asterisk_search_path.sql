-- F10 fix: Asterisk res_config_pgsql queries unqualified table names
-- (ps_endpoints, …). Role sv_asterisk must resolve them via search_path.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'sv_asterisk') THEN
        ALTER ROLE sv_asterisk SET search_path TO asterisk, public;
    END IF;
END
$$;
