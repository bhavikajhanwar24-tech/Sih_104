-- AWS single-box app DB (POSTGRES_DB=sentinelvoice). Flyway creates every table,
-- including schema asterisk, so the local Asterisk-mirror init script is not mounted here.
-- Passwords must match /opt/sentinelvoice-secrets/app.env and docker-compose.aws.yml.

CREATE ROLE sv_owner LOGIN PASSWORD 'changeme_owner' NOSUPERUSER BYPASSRLS;
CREATE ROLE sv_app LOGIN PASSWORD 'changeme_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
-- V017 only creates sv_asterisk when missing, and sv_owner cannot CREATE ROLE.
CREATE ROLE sv_asterisk LOGIN PASSWORD 'changeme_asterisk' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

ALTER DATABASE sentinelvoice OWNER TO sv_owner;
GRANT CONNECT ON DATABASE sentinelvoice TO sv_owner, sv_app;

ALTER SCHEMA public OWNER TO sv_owner;
GRANT USAGE, CREATE ON SCHEMA public TO sv_owner;
GRANT USAGE ON SCHEMA public TO sv_app;

ALTER DEFAULT PRIVILEGES FOR ROLE sv_owner IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sv_app;
ALTER DEFAULT PRIVILEGES FOR ROLE sv_owner IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO sv_app;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- V022 runs ALTER ROLE sv_asterisk; Flyway (sv_owner) needs CREATEROLE + ADMIN on that role.
-- infra/aws/deploy.sh revokes both once the backend is healthy.
ALTER ROLE sv_owner CREATEROLE;
GRANT sv_asterisk TO sv_owner WITH ADMIN OPTION, INHERIT FALSE, SET FALSE;
