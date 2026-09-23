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
| 2026-09-19 | LLM calls only via Inference-plane `llm_gateway` (never in 500ms path); MockProvider labelled `provider:mock` | F5 — provider-agnostic gateway |
| 2026-09-19 | PDF extraction uses layout strip (headers/footers/tables) + per-clause chunks; injection flags store phrase+char range | F5 chunking quality |
| 2026-09-19 | LLM health exposes ollama/openaiCompat/activeProvider/degraded (no false reachable) | F5 Settings agreement |
| 2026-09-20 | Default Ollama model gemma3:4b; structured format schema; selftest; concurrency 1 | F5 small-model reliability |
| 2026-09-20 | v1 TransactionPolicy YAML/`TransactionScoring` removed; policy is tenant data (DSL + ACTIVE sets) | F6 — LLM proposes, humans approve |
| 2026-09-20 | FactCatalogue directory enums bound to `DirectoryMatch`; REJECTED rules kept for Edit-to-fix; POSSIBLE_DUPLICATE flag; level clamp down-only | F6 follow-up quality |
| 2026-09-20 | Level floors (credential never-share/ask & unverified-accept →3; approval/verify →2) with LEVEL_ADJUSTED up | F6 level bounds |
| 2026-09-20 | v1 ContextScoring / RelationshipScoring / CrossChannelScoring YAML deleted; TRANSACTION score from ACTIVE DSL rules | F7 runtime rule engine |
| 2026-09-20 | Deployed central DB is Supabase (Session Pooler); local Docker Postgres kept for lab/offline | Shared multi-env system of record |
| 2026-09-21 | v1 YAML `sentinelvoice.fusion` / `intervention` / `emergency` deleted; decisions use tenant `fusion_configs` + pure `FusionEngine` | F8 — per-tenant fusion config |
| 2026-09-21 | Frontend Risk tuning page at `/app/settings/risk-tuning` (draft/approve/what-if) | F8 — fusion UI |
| 2026-09-21 | Static caller/agent PJSIP endpoints replaced by PJSIP REALTIME (`asterisk.ps_*`); AGI resolves via Java `/internal/v2` | F10 — multi-tenant telephony |
| 2026-09-21 | `DirectoryMatch.NumberProvenance.SUSPECT_TRUNK` from `trunks.cli_prefixes` | F10 — CLI provenance |
| 2026-09-21 | Directory Telephony tab + Settings → Telephony UI; SIP reg/on-call indicators | F10 — telephony UI |
| 2026-09-21 | Telephony health explains Postgres/AMI/ARI/SIP_EXTERNAL_IP; DotEnv + AMI :5038 | F10 — health diagnostics |
| 2026-09-21 | Asterisk mirror uses `AsteriskMirrorJdbc` (not a `DataSource`/`JdbcTemplate` bean) | F10 — avoid stealing primary pool when `ASTERISK_SYNC_URL` is set |
| 2026-09-22 | Live Calls page + `/api/v2/calls`; same-tenant dial enforce; registration via `ps_contacts` | F10 — lab acceptance path |
| 2026-09-22 | Forensic dossier owned by F12 (not F15); `/api/v1/forensics` removed in favour of `/api/v2/sessions/{id}/dossier*` | F12 — explainability + court PDF |
| 2026-09-22 | Asterisk entrypoint always writes `sorcery.conf` realtime; `sv_asterisk` search_path=`asterisk` (V022) | F10 — softphone REGISTER was dead (no endpoints) |
| 2026-09-22 | Local Asterisk Postgres must include V020 `ps_contacts` columns or REGISTER auth succeeds but contact bind fails | F10 — lab mirror not Flyway-managed |
| 2026-09-22 | Dialplan sets caller endpoint as CALLERID before Originate; Progress until answer | F10 — Zoiper mobile 486 when From CID missing |
| 2026-09-22 | Hangup finalize via `sv_session_end.sh` (curl), not AGI; explicit NO_ANSWER end | F10 — Live Calls stuck when hangup AGI failed |
| 2026-09-22 | F12 verify pass: dossier privacy wording; Why lang=hi; History employee filter; policy citation deep-link | F12 — verification checklist |
| 2026-09-22 | Audit hash uses `Instant` truncated to micros; dossier chain verify is session-scoped (not MIN..MAX) | F12 — `auditChainValid` false from PG timestamptz rounding + interstitial seqs |
| 2026-09-22 | Live Calls upgraded to multi-call operator workspace (L3 amber/alert/callback/lock, Bridge=`calls:bridge`, stale banner) | F10 — manual acceptance for operator console |
| 2026-09-22 | F12 Mark FP / Confirmed live (`POST …/review`); Force L3 records operator explain ticks | F12 — closed review stubs + softphone empty timeline |
| 2026-09-23 | F15 consent/passport/retention/DSR; audit_blocks never purged; `/app/compliance`; v1 compliance re-pointed | F15 — tenant DPDP-oriented controls |
| 2026-09-23 | F17 integrations migration renumbered V025→V028 (F15 already occupied V025–V027 on this branch) | Avoid Flyway version clash on merge |
| 2026-09-23 | F16 session_labels + analytics MVs; fairness from directory tags only (never voice); L3 suggestion → fusion DRAFT | F16 — labelled metrics / fairness / threshold UI |
| 2026-09-23 | F18 demo seed/reset (`is_demo`); JSON scenarios + `/app/lab`; delete v1 YAML/TrajectoryRunner inject | F18 — demo tenants + real-audio lab |

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
| 2026-09-19 | `tika-core` + `tika-parsers-standard-package` + `pdfbox` | Decision | Policy doc sniff + extract (F5) |
| 2026-09-19 | `resilience4j-spring-boot3` + circuitbreaker + `spring-boot-starter-aop` | Decision | LLM gateway client CB (F5) |
| 2026-09-19 | `jsonschema` (already listed in ml pyproject) | Inference | Gateway output validation (F5) |
| 2026-09-20 | `caffeine` (Boot-managed) | Decision | Per-tenant ACTIVE policy compile cache (F7) |

## Feature log

| Date | Feature | Summary |
|------|---------|---------|
| 2026-09-19 | F0 | Reset project rules, V2 architecture record, version contracts, repo hygiene |
| 2026-09-19 | F1 | PostgreSQL 16 + Flyway; replace H2; audit_blocks + verify API |
| 2026-09-19 | F2 | Tenant register, JWT cookies, RBAC, /me, TenantContext, React shell + Users |
| 2026-09-19 | F3 | RLS + SECURITY DEFINER; tenant STOMP topics; ML service token; Audit page |
| 2026-09-19 | F4 | Tenant directory CRUD/import/resolve/relationships; `/app/directory` UI |
| 2026-09-19 | F5 | Policy document ingest/extract/chunks; LLM gateway (Ollama/OpenAI-compat/mock); Policies + Settings AI UI |
| 2026-09-20 | F6 | PolicyRule DSL + fact catalogue; async compile; versioned sets; review/approve UI; remove v1 TransactionPolicy |
| 2026-09-20 | F6 | Chunk diagnostics + COMPLETED_NO_RULES; quote normalisation; 90s retry; empty review + re-run failed |
| 2026-09-20 | F6 | Catalogue-enum schema; Indian number grounding; level caps; plain-English; cancel/delete/max-docs |
| 2026-09-20 | F6 | Verification pass: edit quote grounding; no silent mock; Keywords tab; verify_f6.sql |
| 2026-09-20 | F6 | Quality follow-up: VALUE_NOT_IN_SOURCE; no invented clauseRef; max-docs=1; must-never level cap; F6.md checklist + Quick Policy re-compile |
| 2026-09-20 | F7 | Runtime RuleEngine + FactAssembler; simulate/status APIs; Simulation UI; delete v1 ContextScoring |
| 2026-09-20 | F6 | Empty-LLM fix: outcome statuses, truncated vs empty, subset schema, suspect retry, progress clamp, test-clause |
| 2026-09-20 | Ops | Supabase central DB + `migrate-to-supabase.ps1` local dump/restore; docs/v2/SUPABASE.md |
| 2026-09-21 | F8 | Per-tenant fusion_configs + pure FusionEngine; Risk tuning UI; YAML fusion/intervention removed |
| 2026-09-21 | F10 | Multi-tenant Asterisk PJSIP REALTIME; AGI dialplan; Directory/Settings telephony UI |
| 2026-09-22 | F11 | Streaming ASR + Stage A/B intent; FeatureFrame linguistic (no transcript on wire); gate-check; languages settings |
| 2026-09-22 | F12 | session_ticks/reasons/extractions; ForensicDossier from DB; `/api/v2/sessions` + Call History/Detail; verify-dossier |
| 2026-09-23 | F15 | Consent, notice, retention, DSR, encrypted passports; `/app/compliance` |
| 2026-09-23 | F16 | session_labels, analytics overview/rules/fairness, L3 suggestion→draft, `/app/analytics` |
| 2026-09-23 | F18 | Demo seed/reset, Demo Bank + Demo Insurer, lab simulator `/app/lab`, `DEMO_SCRIPT.md` |

