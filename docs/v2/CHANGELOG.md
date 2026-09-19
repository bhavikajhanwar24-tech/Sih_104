# SentinelVoice V2 — Changelog

Track deviations from superseded v1 docs/rules, new dependencies, and notable V2 milestones.
Keep entries short (one line reason where possible).

## Deviations from v1

| Date | Deviation | Reason |
|------|-----------|--------|
| 2026-09-19 | Superseded `.cursor/rules/sentinelvoice.mdc` (frozen contracts, small diffs, STOP-and-ask, all-tunables-in-yml, no sessionStorage) | P-GLOBAL / F0 — V2 multi-tenant policy platform |
| 2026-09-19 | `docs/00_PROJECT_CONTEXT.md` and `docs/01_EXECUTION_PLAN.md` are historical only | P-GLOBAL — not authoritative for V2 |
| 2026-09-19 | Contracts relocated: `docs/contracts/` → `docs/contracts/v1/`; `docs/contracts/v2/` is source of truth | F0 — versioned multi-tenant wire formats |
| 2026-09-19 | `scripts/gen-contracts.sh` / `gen-validators.mjs` / `validate-contracts.sh` read `docs/contracts/v2` | F0 — generators may fail until producers/consumers adopt v2 fields |
| 2026-09-19 | Frontend `contracts.test.js` still references `docs/contracts/fixtures/...` (pre-move path) | Left for F20 test reconciliation — do not rewrite tests in F0 |
| 2026-09-19 | H2 removed; PostgreSQL 16 + Flyway required; ddl-auto=validate; dual DB roles | F1 — multi-tenant foundation |
| 2026-09-19 | v1 JPA entities (directory, passport, consent, edges, cross-channel, caller profile) deleted | F1 — disposable single-tenant schema; re-create in F4/F12/F15 |
| 2026-09-19 | Decision Plane default port **8081** (was 8080) | Avoid host conflict with other services on 8080 |
| 2026-09-19 | v1 HTTP Basic + permitAll SecurityConfig replaced by JWT cookie auth + CSRF | F2 — multi-tenant self-registration |
| 2026-09-19 | v1 four-view AppShell / DEMO_USERS Basic login removed | F2 — react-router shell + permissions[] menus |
| 2026-09-19 | FORCE RLS on tenant tables; client `tenantId` query removed from audit verify | F3 — isolation |
| 2026-09-19 | v1 TrunkClassifier + DirectoryService stubs replaced by F4 DirectoryMatch resolve | F4 — tenant directory |

## New dependencies

| Date | Dependency | Plane | One-line justification |
|------|------------|-------|------------------------|
| 2026-09-19 | `flyway-core` + `flyway-database-postgresql` | Decision | Versioned SQL migrations; no Hibernate ddl-auto |
| _(postgresql driver already present)_ | `org.postgresql:postgresql` | Decision | JDBC driver for PG 16 |
| 2026-09-19 | `jjwt-api/impl/jackson` 0.12.6 | Decision | Access JWT for cookie sessions |
| 2026-09-19 | `googleauth` 1.5.0 | Decision | Optional TOTP MFA |
| 2026-09-19 | `bcprov-jdk18on` 1.78.1 | Decision | Argon2id password hashing |
| 2026-09-19 | `react-router-dom` ^6 | Frontend | Multi-page auth shell |
| 2026-09-19 | (none new) | — | F3 uses existing PG RLS + Spring WS |
| 2026-09-19 | `libphonenumber` 8.13.50 | Decision | E.164 normalisation for directory phones |
| 2026-09-19 | `commons-csv` 1.11.0 + `poi-ooxml` 5.2.5 | Decision | Directory CSV/XLSX import |

## Feature log

| Date | Feature | Summary |
|------|---------|---------|
| 2026-09-19 | F0 | Reset project rules, V2 architecture record, version contracts, repo hygiene |
| 2026-09-19 | F1 | PostgreSQL 16 + Flyway; replace H2; audit_blocks + verify API |
| 2026-09-19 | F2 | Tenant register, JWT cookies, RBAC, /me, TenantContext, React shell + Users |
| 2026-09-19 | F3 | RLS + SECURITY DEFINER; tenant STOMP topics; ML service token; Audit page |
| 2026-09-19 | F4 | Tenant directory CRUD/import/resolve/relationships; `/app/directory` UI |
