# SentinelVoice V2 — Architecture

Multi-tenant, policy-aware real-time voice-fraud interdiction. This document is the V2
architecture record. Historical v1 context lives under `docs/00_PROJECT_CONTEXT.md` and is
**not** authoritative where it conflicts with this file or P-GLOBAL.

## Planes and components

```mermaid
flowchart TB
  subgraph Media["Media Plane"]
    WebRTC[WebRTC / softphone]
    PBX[Asterisk / AudioSocket / carrier bridge]
    Norm[Normaliser + ring feed]
  end

  subgraph Inference["Inference Plane — Python FastAPI"]
    Ring[Bounded PCM ring buffer]
    Fast[Fast path DSP / antispoof / speaker / watermark]
    Slow[Slow path ASR streaming]
    LlmFeat[LLM feature extraction — async]
  end

  subgraph Decision["Decision Plane — Java Spring Boot"]
    TenantAuth[Tenant / Auth service]
    Directory[Directory service]
    Policy[Policy service — documents, compiler, versions]
    Rules[Rule engine]
    Fusion[Fusion engine — per-tenant config]
    Response[Response matrix + Actuation]
    LlmGw[LLM gateway — local Ollama; optional external offline compile]
    Audit[Audit ledger — per tenant, hash-chained]
  end

  subgraph Presentation["Presentation Plane — React"]
    Analyst[Analyst / Supervisor consoles]
    Admin[Control surface — Admin console]
  end

  PG[(PostgreSQL 16 + Flyway + RLS)]

  WebRTC --> Norm
  PBX --> Norm
  Norm --> Ring
  Ring --> Fast
  Ring --> Slow
  Slow --> LlmFeat
  Fast -->|FeatureFrame v2| Fusion
  Slow -->|structured labels| Fusion
  LlmFeat -->|features only, never decisions| Fusion
  TenantAuth --> PG
  Directory --> PG
  Policy --> PG
  Rules --> PG
  Fusion --> Rules
  Fusion --> Directory
  Fusion --> Response
  Fusion --> Audit
  Response --> Audit
  Policy --> LlmGw
  LlmGw -.->|offline policy compile only| Policy
  Audit --> PG
  Fusion -->|TelemetryFrame v2 STOMP| Analyst
  Admin --> TenantAuth
  Admin --> Policy
  Admin --> Directory
  Admin --> Response
```

## Sequence — (a) tenant onboarding

```mermaid
sequenceDiagram
  participant Ops as Platform ops
  participant Admin as Admin console
  participant Auth as Tenant/Auth
  participant Pol as Policy service
  participant Dir as Directory
  participant DB as PostgreSQL
  participant Audit as Audit ledger

  Ops->>Admin: Create tenant + first admin
  Admin->>Auth: POST tenant + admin principal
  Auth->>DB: Insert tenant, roles (RLS policies)
  Auth->>Audit: TENANT_CREATED
  Admin->>Dir: Seed / import directory rows
  Dir->>DB: Upsert directory (tenant_id)
  Dir->>Audit: DIRECTORY_UPDATED
  Admin->>Pol: Upload policy documents
  Pol->>Pol: Compile clauses → versioned pack
  Pol->>DB: Store document + compiled version
  Pol->>Audit: POLICY_VERSION_PUBLISHED
  Admin->>Auth: Configure retention / response matrix
  Auth->>DB: Persist tenant config
  Auth->>Audit: CONFIG_PUBLISHED
```

## Sequence — (b) live call from ring to actuation

```mermaid
sequenceDiagram
  participant Media as Media Plane
  participant ML as ml-engine ring
  participant Fast as Fast path
  participant ASR as ASR / LLM features
  participant Dec as Decision Plane
  participant Fuse as Fusion + ladder
  participant Act as Actuation
  participant UI as Analyst UI
  participant DB as PostgreSQL / Audit

  Media->>ML: PCM frames (never leave host)
  loop every 500 ms
    ML->>Fast: window (<=120 ms budget)
    Fast->>Dec: FeatureFrame v2 (tenantId, no audio)
    Dec->>Fuse: load tenant weights/thresholds (cache)
    Fuse->>Fuse: rules + directory lookups (<10 ms)
    Fuse->>Fuse: score + ladder (<20 ms)
    Fuse->>UI: TelemetryFrame v2
    Fuse->>DB: audit append if state change
  end
  ML->>ASR: streaming ASR (0.3–0.9 s behind)
  ASR->>Dec: redacted labels / async LLM features (1–3 s)
  Note over Fuse: Sync approval-gate may wait <=800 ms<br/>for freshest LLM features
  Fuse->>Act: hold / MFA / terminate / freeze
  Act->>DB: actuation audited
```

## Latency budget

| Stage | Budget | Notes |
|-------|--------|--------|
| Fast path (per 500 ms window) | ≤ 120 ms | DSP, antispoof, speaker, watermark attribution |
| Rule / directory lookup | < 10 ms | Tenant-cached config; DB miss is a degradation event |
| Fusion + intervention ladder | < 20 ms | Deterministic; no LLM in this path |
| ASR streaming lag | 0.3–0.9 s behind speech | Labels only; no verbatim persistence |
| LLM feature extraction | 1–3 s async | Never blocks the fast path |
| Sync approval-gate wait | ≤ 800 ms | May wait for freshest LLM features before irreversible action |

## Trust boundaries

| Zone | What lives here | May leave? |
|------|-----------------|------------|
| Media + ml-engine ring | Raw PCM (≤8 s, overwritten) | **Never** — not to Java, DB, disk, logs, or LLM |
| Inference → Decision wire | FeatureFrame numbers, enums, redacted structured labels | Yes — no audio, no full transcript |
| Decision Plane memory | Session state, tenant config cache, risk / level | Tenant-scoped; audited on change |
| PostgreSQL | Tenant config, directory, policy versions, audit chain, redacted derived labels | RLS + `tenant_id`; no PCM, no full transcripts |
| LLM gateway | Redacted text / clause text for **feature extraction** or offline **policy compile** | Prompt-injection hardened; outputs are features only — never scores/levels/actions |
| Presentation | Telemetry + admin APIs | AuthZ by role / `permissions[]`; JWT httpOnly cookies (F2); no raw audio |

**What may cross to the LLM:** redacted ASR snippets or structured clause text under tenant policy compile / feature prompts. **Never:** raw audio, full unredacted call transcripts, other tenants' data, or authority to set risk/level/action.

## Threat model (V2-specific)

| Risk | Mitigation (design) |
|------|---------------------|
| Prompt injection via caller speech | ASR → redaction → allowlisted feature schema; LLM outputs validated against typed feature DTOs; fusion ignores free-text; LLM cannot set score/level/action |
| Prompt injection via uploaded policy documents | Policy compile in isolated job; strip active content; compiled clauses are structured IDs/predicates reviewed before publish; publish requires admin role + audit |
| Malicious / compromised tenant admin weakens thresholds | Versioned config; dual-control / approval for high-impact publishes (later features); every change on hash-chained audit ledger; platform-level floors optional |
| Cross-tenant data leakage | `tenant_id` on every row; PostgreSQL RLS; no shared caches without tenant key; contracts require `tenantId` |
| Replay of admin actions | Nonce / request id + audited mutations; httpOnly session cookies (preferred); short-lived tokens if used; CSRF protection on cookie sessions |

## Related docs

- Feature notes: [`docs/v2/features/`](features/)
- Changelog / deviations: [`CHANGELOG.md`](CHANGELOG.md)
- Contracts: [`docs/contracts/v2/`](../contracts/v2/)
