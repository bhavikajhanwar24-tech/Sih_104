-- Asterisk 20 PJSIP realtime needs extra columns on ps_contacts / ps_aors.
-- Without these, REGISTER authenticates but fails to bind contact → UI stays Unregistered.

ALTER TABLE asterisk.ps_contacts
    ADD COLUMN IF NOT EXISTS qualify_timeout DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS reg_server TEXT,
    ADD COLUMN IF NOT EXISTS authenticate_qualify TEXT,
    ADD COLUMN IF NOT EXISTS prune_on_boot TEXT,
    ADD COLUMN IF NOT EXISTS qualify_2xx_only TEXT;

ALTER TABLE asterisk.ps_aors
    ADD COLUMN IF NOT EXISTS contact TEXT,
    ADD COLUMN IF NOT EXISTS qualify_timeout DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS authenticate_qualify TEXT,
    ADD COLUMN IF NOT EXISTS qualify_2xx_only TEXT,
    ADD COLUMN IF NOT EXISTS minimum_expiration INTEGER,
    ADD COLUMN IF NOT EXISTS default_expiration INTEGER,
    ADD COLUMN IF NOT EXISTS maximum_expiration INTEGER,
    ADD COLUMN IF NOT EXISTS support_path TEXT,
    ADD COLUMN IF NOT EXISTS remove_unavailable TEXT;

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'sv_asterisk') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON asterisk.ps_contacts TO sv_asterisk;
        GRANT SELECT, INSERT, UPDATE, DELETE ON asterisk.ps_aors TO sv_asterisk;
    END IF;
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'sv_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON asterisk.ps_contacts TO sv_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON asterisk.ps_aors TO sv_app;
    END IF;
END
$$;
