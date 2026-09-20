# SentinelVoice V2

SentinelVoice V2 is a **multi-tenant, policy-aware** real-time voice-fraud interdiction
platform for enterprises (banks, insurers, government bodies). It sits on live voice calls,
fuses acoustic evidence with tenant policy, directory, and transaction context, and can
intervene mid-call — while keeping raw PCM only in a bounded, overwritten ring buffer in the
Inference Plane (never in Java, never on disk).

This repository still contains v1 single-tenant prototype code as raw material. V2 work
**replaces** that design where it conflicts; see [`docs/v2/`](docs/v2/).

## Architecture (planes)

| Plane | Role |
|-------|------|
| Media | Ephemeral PCM capture / normalise |
| Inference (Python) | Ring buffer + feature extraction (no persisted audio/transcripts) |
| Decision (Java) | Tenant/auth, directory, policy, rules, fusion, actuation, audit |
| Presentation (React) | Analyst consoles + **Admin control surface** |

Full diagrams, latency budgets, and threat model: [`docs/v2/ARCHITECTURE.md`](docs/v2/ARCHITECTURE.md).

## Quickstart

> **Filled in by F19.** Until then, local bring-up remains the v1 host/compose scripts and may
> not reflect multi-tenant V2 behaviour.

Placeholder:

```bash
# Coming in F19 — multi-tenant Docker Compose + seed tenants
# See docs/v2/features/ once F19 lands.
```

Historical v1 host commands (prototype only): `.\make.cmd help` / `.\make.cmd dev`.

## Documentation

| Doc | Purpose |
|-----|---------|
| [`docs/v2/ARCHITECTURE.md`](docs/v2/ARCHITECTURE.md) | V2 architecture record |
| [`docs/v2/CHANGELOG.md`](docs/v2/CHANGELOG.md) | Deviations from v1, dependencies, features |
| [`docs/v2/SUPABASE.md`](docs/v2/SUPABASE.md) | Deployed central DB (Supabase) + local→cloud migration |
| [`docs/v2/features/`](docs/v2/features/) | Per-feature notes + manual verification (through F5) |
| [`docs/contracts/v2/`](docs/contracts/v2/) | Wire contracts (source of truth) |
| [`docs/contracts/v1/`](docs/contracts/v1/) | Historical v1 schemas |
| [`docs/00_PROJECT_CONTEXT.md`](docs/00_PROJECT_CONTEXT.md) | **Historical** v1 context only |

## Non-negotiable principles

- Java never receives raw audio.
- No full call transcripts persisted — structured redacted labels only.
- Risk decisions and config changes are hash-chain audited per tenant.
- The LLM emits features only; deterministic code decides.
- Every tenant-owned row carries `tenant_id` (RLS + app checks).
