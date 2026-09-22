-- Local lab Asterisk PJSIP REALTIME (schema asterisk).
-- App DB is Supabase; this Docker Postgres is only the Asterisk mirror (:15432).
-- Keep in sync with Flyway V017/V018/V020/V022 column sets.

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sv_asterisk') THEN
    CREATE ROLE sv_asterisk LOGIN PASSWORD 'changeme_asterisk'
      NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
  END IF;
END
$$;

GRANT CONNECT ON DATABASE sentinelvoice TO sv_asterisk;

CREATE SCHEMA IF NOT EXISTS asterisk;
REVOKE ALL ON SCHEMA asterisk FROM PUBLIC;
GRANT USAGE ON SCHEMA asterisk TO sv_asterisk;
GRANT USAGE ON SCHEMA asterisk TO sv_app;
GRANT USAGE ON SCHEMA asterisk TO sv_owner;

ALTER ROLE sv_asterisk SET search_path TO asterisk, public;

CREATE TABLE IF NOT EXISTS asterisk.ps_endpoints (
    id                              TEXT PRIMARY KEY,
    transport                       TEXT,
    aors                            TEXT,
    auth                            TEXT,
    context                         TEXT,
    disallow                        TEXT,
    allow                           TEXT,
    direct_media                    TEXT,
    rtp_symmetric                   TEXT,
    force_rport                     TEXT,
    rewrite_contact                 TEXT,
    ice_support                     TEXT,
    media_use_received_transport    TEXT,
    media_encryption                TEXT,
    inband_progress                 TEXT,
    media_address                   TEXT
);

CREATE TABLE IF NOT EXISTS asterisk.ps_auths (
    id          TEXT PRIMARY KEY,
    auth_type   TEXT,
    password    TEXT,
    username    TEXT
);

CREATE TABLE IF NOT EXISTS asterisk.ps_aors (
    id                      TEXT PRIMARY KEY,
    max_contacts            INTEGER,
    remove_existing         TEXT,
    qualify_frequency       INTEGER,
    contact                 TEXT,
    qualify_timeout         DOUBLE PRECISION,
    authenticate_qualify    TEXT,
    qualify_2xx_only        TEXT,
    minimum_expiration      INTEGER,
    default_expiration      INTEGER,
    maximum_expiration      INTEGER,
    support_path            TEXT,
    remove_unavailable      TEXT
);

CREATE TABLE IF NOT EXISTS asterisk.ps_contacts (
    id                          TEXT PRIMARY KEY,
    uri                         TEXT,
    expiration_time             BIGINT,
    qualify_frequency           INTEGER,
    outbound_proxy              TEXT,
    path                        TEXT,
    user_agent                  TEXT,
    endpoint                    TEXT,
    via_addr                    TEXT,
    via_port                    INTEGER,
    call_id                     TEXT,
    regenerate_qualify_answer   TEXT,
    qualify_timeout             DOUBLE PRECISION,
    reg_server                  TEXT,
    authenticate_qualify        TEXT,
    prune_on_boot               TEXT,
    qualify_2xx_only            TEXT
);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA asterisk TO sv_asterisk;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA asterisk TO sv_app;
GRANT ALL ON ALL TABLES IN SCHEMA asterisk TO sv_owner;
