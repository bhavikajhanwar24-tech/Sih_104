-- F18: demo tenant flag + seed support

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS is_demo BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_tenants_is_demo ON tenants (is_demo) WHERE is_demo;

COMMENT ON COLUMN tenants.is_demo IS
    'F18 — true for seeded demo tenants; reset endpoint deletes only these.';

-- Ensure SUPERVISOR is allowed on users (V023 may already have done this)
DO $$
BEGIN
    ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
    ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN (
        'TENANT_ADMIN', 'POLICY_APPROVER', 'ANALYST', 'AUDITOR', 'SUPERVISOR'
    ));
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;
