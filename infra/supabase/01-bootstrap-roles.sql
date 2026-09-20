-- SentinelVoice on Supabase — create runtime role + grants.
-- Run as the project `postgres` role (Dashboard SQL or psql via Session Pooler).
--
-- On managed Supabase, `postgres` is not a full superuser and typically cannot
-- CREATE ROLE … BYPASSRLS. Flyway therefore uses the project postgres role
-- (DB_OWNER_USER), while the app pool uses sv_app (no BYPASSRLS).
--
-- SECURITY DEFINER helpers (fn_*) must be owned by a BYPASSRLS role so they
-- still work under FORCE RLS — after Flyway/restore, keep them owned by postgres
-- (see 02-post-restore-grants.sql).

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sv_app') THEN
    CREATE ROLE sv_app LOGIN PASSWORD 'changeme_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
  END IF;
END
$$;

-- Replace password after first connect (script also does this from env):
-- ALTER ROLE sv_app WITH PASSWORD '<SV_APP_PASSWORD>';

GRANT CONNECT ON DATABASE postgres TO sv_app;
GRANT USAGE ON SCHEMA public TO sv_app;

-- App may use tables/sequences created by postgres (Flyway owner on Supabase).
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sv_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO sv_app;

-- Existing objects (after restore / migrate):
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO sv_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sv_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO sv_app;
