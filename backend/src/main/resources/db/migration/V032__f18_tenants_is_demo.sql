-- F18 follow-up: ensure tenants.is_demo exists.
-- V030 may have been recorded earlier as a different script (clear_all) on some DBs.
ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS is_demo BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_tenants_is_demo ON tenants (is_demo) WHERE is_demo;

COMMENT ON COLUMN tenants.is_demo IS
    'F18 — true for seeded demo tenants; reset endpoint deletes only these.';
