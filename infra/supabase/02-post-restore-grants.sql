-- Post-restore / post-Flyway grants for SentinelVoice on Supabase.
-- Ensures sv_app can use public objects and SECURITY DEFINER fn_* stay owned
-- by postgres (BYPASSRLS) under FORCE ROW LEVEL SECURITY.

-- Ownership of platform helpers → postgres (bypass FORCE RLS when invoked)
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN
    SELECT p.oid::regprocedure AS sig
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.proname LIKE 'fn_%'
  LOOP
    EXECUTE format('ALTER FUNCTION %s OWNER TO postgres', r.sig);
  END LOOP;
END
$$;

GRANT USAGE ON SCHEMA public TO sv_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO sv_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sv_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO sv_app;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sv_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO sv_app;

-- Re-assert FORCE RLS on every public base table that has a tenant_isolation policy
-- (Supabase migration notes warn RLS flags can be dropped depending on dump flags).
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN
    SELECT c.relname AS tbl
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_policy p ON p.polrelid = c.oid
    WHERE n.nspname = 'public'
      AND c.relkind = 'r'
      AND p.polname = 'tenant_isolation'
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', r.tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', r.tbl);
  END LOOP;
END
$$;
