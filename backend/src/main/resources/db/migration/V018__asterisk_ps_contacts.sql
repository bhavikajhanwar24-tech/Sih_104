-- F10 follow-up: optional PJSIP contacts realtime table (registration cache).
-- Asterisk can keep contacts in-memory without this; adding it for full REALTIME parity.

CREATE TABLE IF NOT EXISTS asterisk.ps_contacts (
    id                  TEXT PRIMARY KEY,
    uri                 TEXT,
    expiration_time     BIGINT,
    qualify_frequency   INTEGER,
    outbound_proxy      TEXT,
    path                TEXT,
    user_agent          TEXT,
    endpoint            TEXT,
    via_addr            TEXT,
    via_port            INTEGER,
    call_id             TEXT,
    regenerate_qualify_answer TEXT
);

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'sv_asterisk') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON asterisk.ps_contacts TO sv_asterisk;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'sv_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON asterisk.ps_contacts TO sv_app;
    END IF;
END
$$;
