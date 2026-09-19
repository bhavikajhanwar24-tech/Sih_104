-- F4: Tenant directory — people, org, authority, phones, externals, relationships, imports

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

CREATE TABLE departments (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants (id),
    name        TEXT NOT NULL,
    parent_id   UUID REFERENCES departments (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_departments_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE employees (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    employee_code   TEXT NOT NULL,
    full_name       TEXT NOT NULL,
    email           TEXT,
    department_id   UUID REFERENCES departments (id),
    job_title       TEXT,
    role_key        TEXT,
    manager_id      UUID REFERENCES employees (id),
    status          TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ON_LEAVE', 'TRAVELLING', 'SUSPENDED', 'TERMINATED')),
    status_until    TIMESTAMPTZ,
    status_note     TEXT,
    high_authority  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_employees_tenant_code UNIQUE (tenant_id, employee_code)
);

CREATE UNIQUE INDEX uq_employees_tenant_email_lower
    ON employees (tenant_id, lower(email))
    WHERE email IS NOT NULL AND length(trim(email)) > 0;

CREATE TABLE employee_authority (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants (id),
    employee_id             UUID NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    action_type             TEXT NOT NULL,
    max_amount_inr          NUMERIC(18, 2),
    requires_dual_approval  BOOLEAN NOT NULL DEFAULT FALSE,
    allowed_channels        TEXT[] NOT NULL DEFAULT ARRAY['VOICE']::text[],
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE employee_phones (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    employee_id     UUID NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    e164            TEXT NOT NULL,
    label           TEXT NOT NULL CHECK (label IN ('OFFICE', 'MOBILE', 'HOME')),
    is_primary      BOOLEAN NOT NULL DEFAULT FALSE,
    sip_extension   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_employee_phones_tenant_e164 UNIQUE (tenant_id, e164)
);

CREATE TABLE external_parties (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants (id),
    name        TEXT NOT NULL,
    type        TEXT NOT NULL
        CHECK (type IN ('VENDOR', 'CUSTOMER', 'REGULATOR', 'PARTNER', 'OTHER')),
    phones      JSONB NOT NULL DEFAULT '[]'::jsonb,
    verified    BOOLEAN NOT NULL DEFAULT FALSE,
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE beneficiary_accounts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (id),
    external_party_id   UUID REFERENCES external_parties (id) ON DELETE SET NULL,
    account_ref_hash    TEXT NOT NULL,
    label               TEXT,
    first_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified            BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE known_relationships (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (id),
    from_employee_id    UUID NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    to_employee_id      UUID REFERENCES employees (id) ON DELETE CASCADE,
    to_external_id      UUID REFERENCES external_parties (id) ON DELETE CASCADE,
    relationship_type   TEXT NOT NULL DEFAULT 'COLLEAGUE',
    typical_topics      TEXT[] NOT NULL DEFAULT ARRAY[]::text[],
    last_contact_at     TIMESTAMPTZ,
    contact_count       INT NOT NULL DEFAULT 0,
    CONSTRAINT chk_known_rel_one_target CHECK (
        (to_employee_id IS NOT NULL AND to_external_id IS NULL)
        OR (to_employee_id IS NULL AND to_external_id IS NOT NULL)
        OR (to_employee_id IS NULL AND to_external_id IS NULL)
    )
);

CREATE TABLE directory_imports (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants (id),
    uploaded_by     UUID REFERENCES users (id),
    filename        TEXT NOT NULL,
    kind            TEXT NOT NULL
        CHECK (kind IN ('EMPLOYEES', 'PHONES', 'AUTHORITY', 'EXTERNAL_PARTIES')),
    status          TEXT NOT NULL DEFAULT 'DRY_RUN'
        CHECK (status IN ('DRY_RUN', 'COMMITTED', 'FAILED', 'CANCELLED')),
    row_count       INT NOT NULL DEFAULT 0,
    error_report    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_employees_tenant_dept ON employees (tenant_id, department_id);
CREATE INDEX idx_employees_tenant_status ON employees (tenant_id, status);
CREATE INDEX idx_employees_tenant_role ON employees (tenant_id, role_key);
CREATE INDEX idx_employees_tenant_manager ON employees (tenant_id, manager_id);
CREATE INDEX idx_employee_phones_tenant_e164 ON employee_phones (tenant_id, e164);
CREATE INDEX idx_employee_phones_ext ON employee_phones (tenant_id, sip_extension)
    WHERE sip_extension IS NOT NULL;
CREATE INDEX idx_employee_authority_emp ON employee_authority (tenant_id, employee_id);
CREATE INDEX idx_known_rel_from ON known_relationships (tenant_id, from_employee_id);
CREATE INDEX idx_beneficiary_hash ON beneficiary_accounts (tenant_id, account_ref_hash);
CREATE INDEX idx_employees_status_until ON employees (status_until)
    WHERE status_until IS NOT NULL;

-- ---------------------------------------------------------------------------
-- RLS (FORCE + fail-closed via app_current_tenant_id)
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'departments',
        'employees',
        'employee_authority',
        'employee_phones',
        'external_parties',
        'beneficiary_accounts',
        'known_relationships',
        'directory_imports'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I
               USING (tenant_id = app_current_tenant_id())
               WITH CHECK (tenant_id = app_current_tenant_id())',
            t
        );
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO sv_app', t);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- Cross-tenant status expiry (SECURITY DEFINER — bypasses RLS as owner)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_revert_expired_employee_status()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    n integer;
BEGIN
    UPDATE employees
    SET status = 'ACTIVE',
        status_until = NULL,
        status_note = CASE
            WHEN status_note IS NULL OR length(trim(status_note)) = 0
                THEN 'auto-reverted: status_until elapsed'
            ELSE status_note || ' | auto-reverted: status_until elapsed'
        END,
        updated_at = now()
    WHERE status_until IS NOT NULL
      AND status_until < now()
      AND status IN ('ON_LEAVE', 'TRAVELLING');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END;
$$;

REVOKE ALL ON FUNCTION fn_revert_expired_employee_status() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fn_revert_expired_employee_status() TO sv_app;
