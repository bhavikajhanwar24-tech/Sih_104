-- Creates sv_owner (Flyway) and sv_app (runtime).
-- Passwords MUST match .env / .env.example (SV_OWNER_PASSWORD / SV_APP_PASSWORD).
-- Recreate the volume after changing passwords: docker compose down -v && docker compose up -d postgres

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sv_owner') THEN
    -- BYPASSRLS: Flyway owns tables and must still migrate under FORCE RLS (F3).
    CREATE ROLE sv_owner LOGIN PASSWORD 'changeme_owner' NOSUPERUSER BYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sv_app') THEN
    CREATE ROLE sv_app LOGIN PASSWORD 'changeme_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
  END IF;
END
$$;

ALTER DATABASE sentinelvoice OWNER TO sv_owner;

GRANT CONNECT ON DATABASE sentinelvoice TO sv_app;
GRANT CONNECT ON DATABASE sentinelvoice TO sv_owner;

GRANT USAGE, CREATE ON SCHEMA public TO sv_owner;
GRANT USAGE ON SCHEMA public TO sv_app;
ALTER SCHEMA public OWNER TO sv_owner;

ALTER DEFAULT PRIVILEGES FOR ROLE sv_owner IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sv_app;
ALTER DEFAULT PRIVILEGES FOR ROLE sv_owner IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO sv_app;
