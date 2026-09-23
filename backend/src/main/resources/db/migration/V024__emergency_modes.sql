-- F14: emergency / kill-switch columns on tenant_settings
ALTER TABLE tenant_settings
    ADD COLUMN IF NOT EXISTS emergency_mode VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS emergency_mode_set_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS emergency_mode_expires_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS emergency_mode_set_by UUID NULL,
    ADD COLUMN IF NOT EXISTS monitor_only_ttl_minutes INT NOT NULL DEFAULT 60;

ALTER TABLE tenant_settings DROP CONSTRAINT IF EXISTS tenant_settings_emergency_mode_check;
ALTER TABLE tenant_settings ADD CONSTRAINT tenant_settings_emergency_mode_check
    CHECK (emergency_mode IS NULL OR emergency_mode IN ('MONITOR_ONLY', 'SUSPEND_MONITORING'));
