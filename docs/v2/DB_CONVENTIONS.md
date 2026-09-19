# Database conventions (SentinelVoice V2)

## Naming
- Tables and columns: `snake_case`
- Java entities map with explicit `@Column(name = "...")` when names differ

## Keys
- Primary keys are `UUID` with default `gen_random_uuid()` (application may also assign)
- Never use `SERIAL` / `IDENTITY` Long keys for tenant-owned data

## Multi-tenancy
- Every tenant-owned table has `tenant_id UUID NOT NULL` referencing `tenants(id)`
- Application queries always filter by `tenant_id`
- **RLS (F3):** every new tenant-owned table MUST `ENABLE` + `FORCE ROW LEVEL SECURITY` and add
  `tenant_isolation` policy (`tenant_id = app_current_tenant_id()`) **in the same migration** that
  creates the table. `tenants` uses `id = app_current_tenant_id()`. Fail closed when unset.
- Platform pre-auth ops use SECURITY DEFINER functions owned by `sv_owner` (`fn_*`); document each in F3.md
- Runtime role `sv_app` has **no** `BYPASSRLS`. Migration role `sv_owner` has `BYPASSRLS` so Flyway can run under FORCE RLS.
- Java sets `SELECT set_config('app.tenant_id', ?, true)` from `TenantContext` on every pool borrow (`TenantAwareDataSource`)

## Timestamps
- Prefer `created_at` / `updated_at` as `TIMESTAMPTZ NOT NULL DEFAULT now()`
- Store and compare in UTC

## Flexible payloads
- Use `JSONB` for evolving event bodies (e.g. `audit_blocks.payload`)
- Do not store raw audio or full transcripts in JSONB

## Enums
- Prefer `TEXT` columns with `CHECK (col IN (...))`
- Do **not** use PostgreSQL `ENUM` types (painful to migrate)

## Indexes
- Unique constraints for natural keys (e.g. `tenants.slug`, `(tenant_id, seq)` on audit)
- Partial indexes where useful (add in feature migrations with a comment explaining the predicate)

## Roles
- `sv_owner` — Flyway migrations, owns objects, may `ALTER`
- `sv_app` — runtime pool; **no** `SUPERUSER`, **no** `BYPASSRLS`; cannot alter schema
- Immutable tables (audit): `REVOKE UPDATE, DELETE, TRUNCATE` from `sv_app` plus a raise-on-mutate trigger

## Migrations
- Flyway SQL only under `backend/src/main/resources/db/migration/`
- Number sequentially: `V001__...`, `V002__...`
- Never `spring.jpa.hibernate.ddl-auto=update` or `create`
