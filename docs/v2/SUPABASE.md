# Supabase as the deployed central database

SentinelVoice still speaks plain PostgreSQL (Flyway + dual roles). For the
**deployed / shared** environment the system of record is a Supabase Postgres
project. Local Docker Postgres remains available for offline work via the
`local-db` Compose profile.

## Why Session Pooler

Use the **Session pooler** JDBC/URI (port **5432** on `*.pooler.supabase.com`),
not the Transaction pooler (6543). Hibernate and Flyway rely on session features
(prepared statements, `set_config` for `app.tenant_id`).

Add `sslmode=require`. On IPv4-only networks prefer the pooler over
`db.<ref>.supabase.co` (often IPv6-only).

## Roles on Supabase

| Role | Purpose |
|------|---------|
| `postgres` (project) | Flyway / migrations (`DB_OWNER_USER`). Has `BYPASSRLS`. |
| `sv_app` | Runtime Hikari pool (`DB_APP_USER`). **No** `BYPASSRLS`. |

Managed Supabase usually cannot `CREATE ROLE … BYPASSRLS`, so we do **not**
create local-style `sv_owner` there. `SECURITY DEFINER` helpers (`fn_*`) must
stay owned by `postgres` (see `infra/supabase/02-post-restore-grants.sql`).

## One-time data migration (local → Supabase)

1. Create a Supabase project and note the database password.
2. Dashboard → **Connect** → **Session pooler** → copy the URI.
3. In `.env` set (never commit):

```env
USE_SUPABASE=true
SUPABASE_DB_URL=postgresql://postgres.<PROJECT_REF>:<PASSWORD>@aws-0-<REGION>.pooler.supabase.com:5432/postgres?sslmode=require
SUPABASE_DB_PASSWORD=<PASSWORD>
SV_APP_PASSWORD=<choose a strong password for sv_app>

# Decision Plane — point JDBC at the same pooler
DB_URL=jdbc:postgresql://aws-0-<REGION>.pooler.supabase.com:5432/postgres?sslmode=require&options=-c%20TimeZone%3DUTC
DB_APP_USER=sv_app.<PROJECT_REF>
DB_APP_PASSWORD=${SV_APP_PASSWORD}
DB_OWNER_USER=postgres.<PROJECT_REF>
DB_OWNER_PASSWORD=${SUPABASE_DB_PASSWORD}
```

Session Pooler usernames **must** be `role.<PROJECT_REF>` (e.g. `sv_app.abcdef…`).
A bare `sv_app` fails with `ENOIDENTIFIER: no tenant identifier provided`.


4. Ensure local Docker Postgres is up (`docker compose up -d postgres`).
5. Run:

```powershell
.\scripts\migrate-to-supabase.ps1
```

This dumps the local `sentinelvoice` DB, restores into Supabase, creates
`sv_app`, re-applies grants / FORCE RLS, and runs `VACUUM ANALYZE`.

Dump only / restore only:

```powershell
.\scripts\migrate-to-supabase.ps1 -DumpOnly
.\scripts\migrate-to-supabase.ps1 -RestoreOnly
```

6. Restart the Decision Plane so it uses the new `DB_*` values.

## Compose

- Local `postgres` service remains for offline dumps and lab work.
- `backend` reads `DB_URL` / roles from `.env`. For the central cloud DB, set those
  to the Supabase Session Pooler (backend no longer hard-wires `postgres:5432`).
- `backend` does not `depends_on` Postgres health so a Supabase-only deploy works.

## Checklist after cutover

- [ ] Backend starts; Flyway reports schema up to date (no pending migrations).
- [ ] Login works for an existing tenant admin.
- [ ] Audit verify and a directory list return expected rows.
- [ ] `sv_app` cannot `UPDATE`/`DELETE` `audit_blocks` (immutability still holds).
