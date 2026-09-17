# SentinelVoice

SentinelVoice is a real-time voice fraud protection system for banks and contact centres: it detects synthetic / deepfake speech and social-engineering risk *during* the call, fuses acoustic evidence with identity and transaction context, and can intervene mid-sentence — while keeping raw PCM only in a bounded, overwritten ring buffer so the privacy claim is architecture, not a slide.

## Architecture (four planes)

```
┌───────────────────────────────────────────────────────────────────────────────┐
│ ① MEDIA PLANE — carries PCM. Ephemeral. Never persists. Never leaves the host. │
│                                                                                │
│  WebRTC browser tap ─┐                                                         │
│  Asterisk AudioSocket ├──► Normaliser (µ-law/A-law decode, resample,           │
│  Twilio/Exotel bridge ┘     mono, 16-bit) ──► bounded ring buffer (≤8 s)      │
│  SIPREC (architecture only)                          │                         │
└──────────────────────────────────────────────────────┼─────────────────────────┘
                                                       ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│ ② INFERENCE PLANE — Python 3.11 / FastAPI. Stateless per window.               │
│                                                                                │
│   FAST PATH (every 500 ms, budget 120 ms)   SLOW PATH (every 2.5 s, budget 900ms)│
│   • spectral / vocoder artifacts            • Whisper streaming ASR             │
│   • prosody: F0, jitter, shimmer, breath    • intent classifier (urgency /      │
│   • channel: RIR, double-compression          secrecy / authority / the ASK)    │
│   • speaker embedding (ECAPA 192-d)         • entity extraction (name, role,    │
│   • watermark scan (AudioSeal)                amount, beneficiary)              │
│                      │                                    │                    │
│                      └──────────► FeatureFrame JSON ◄──────┘                    │
│                                   (numbers only — no audio, no raw text)        │
└──────────────────────────────────────────┬────────────────────────────────────┘
                                           ▼  WebSocket (persistent)
┌───────────────────────────────────────────────────────────────────────────────┐
│ ③ DECISION PLANE — Java 21 / Spring Boot 3.3. Stateful. Auditable.             │
│                                                                                │
│  Session registry → Identity resolver (CLI ↔ directory ↔ spoken claim ↔        │
│  voiceprint) → Relationship graph → Transaction policy → Cross-channel          │
│  correlator → FUSION ENGINE (weights, EMA, corroboration, hysteresis)           │
│  → INTERVENTION LADDER (FSM) → ACTUATORS (call control, txn lock, OOB MFA)      │
│  → HASH-CHAINED AUDIT LEDGER (SHA-256, persisted)                               │
└──────────────────────────────────────────┬────────────────────────────────────┘
                                           ▼  STOMP /topic/telemetry/{sessionId}
┌───────────────────────────────────────────────────────────────────────────────┐
│ ④ PRESENTATION PLANE — React 18 + TS + Vite + Tailwind                         │
│  Analyst console · Senior Shield · Compliance portal · Red-team lab             │
└───────────────────────────────────────────────────────────────────────────────┘
```

| Plane | Directory | Role |
|-------|-----------|------|
| Media | `gateway/` | Asterisk / WebRTC / PSTN bridges — PCM in, never persisted |
| Inference | `ml-engine/` | FastAPI feature extraction → `FeatureFrame` JSON |
| Decision | `backend/` | Spring Boot fusion, intervention, audit ledger |
| Presentation | `frontend/` | Analyst console and related UIs |

## Prerequisites

- **Java 21**
- **Node 20+**
- **Python 3.11**
- **Docker** (Compose)

## Quickstart

```bash
# List Make targets
make

# Development (stubs until later phases wire them)
make dev

# Individual planes
make backend      # Spring Boot (Decision Plane)
make ml           # FastAPI + reload (Inference Plane)
make frontend     # Vite dev server (Presentation Plane)
make asterisk     # Asterisk container (Media Plane)

# Quality / demo
make test
make eval
make demo
make clean
```

```bash
# Or via Compose (service images land in later phases)
docker compose up
```

Full documentation lives in [`docs/`](docs/) — start with [`docs/00_PROJECT_CONTEXT.md`](docs/00_PROJECT_CONTEXT.md) and [`docs/01_EXECUTION_PLAN.md`](docs/01_EXECUTION_PLAN.md).
