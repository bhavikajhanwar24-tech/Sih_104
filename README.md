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
│  Session registry → Identity resolver → Relationship graph → Transaction policy│
│  → Cross-channel correlator → FUSION ENGINE → INTERVENTION LADDER → ACTUATORS  │
│  → HASH-CHAINED AUDIT LEDGER                                                   │
└──────────────────────────────────────────┬────────────────────────────────────┘
                                           ▼  STOMP /topic/telemetry/{sessionId}
┌───────────────────────────────────────────────────────────────────────────────┐
│ ④ PRESENTATION PLANE — React 18 + Vite + Tailwind                              │
│  Analyst console · Senior Shield · Compliance portal · Red-team lab             │
└───────────────────────────────────────────────────────────────────────────────┘
```

| Plane | Directory | Role | How it runs today |
|-------|-----------|------|-------------------|
| Media | `gateway/` | Asterisk / AudioSocket / WAV replay | **Docker Compose** (`asterisk` service only) |
| Inference | `ml-engine/` | FastAPI feature extraction | **Host** via `.\make.cmd ml` |
| Decision | `backend/` | Spring Boot fusion, intervention, audit | **Host** via `.\make.cmd backend` |
| Presentation | `frontend/` | Analyst console | **Host** via `.\make.cmd frontend` (strict `:5173`) |

> There are **no** Docker images yet for ml-engine / backend / frontend. `docker-compose.yml` intentionally only defines Asterisk — full stack start is `.\make.cmd dev`.

## Prerequisites

- **Java 21**
- **Node 20+**
- **Python 3.11+** with `ml-engine/.venv` (`pip install -e .` from `ml-engine/`)
- **Docker** (for Asterisk)
- Bundled Maven at `tools/apache-maven-3.9.9` (or `mvn` on PATH)
- On Windows: use **`.\make.cmd`** (GNU `make` is optional)

## Quickstart

```bash
# From repo root (Windows PowerShell)
.\make.cmd help

# Ensure Tier-1 anti-spoof checkpoint (no-op if already present)
.\make.cmd ensure-antispoof

# Start Media (Asterisk) → Inference → Decision → Presentation with health waits
.\make.cmd clean
.\make.cmd dev
```

Then open **http://127.0.0.1:5173/** (Vite is pinned to 5173 with `strictPort` — it will **fail** if that port is busy instead of silently moving to 5174).

### Health checks (after `dev`)

| Plane | URL | Expect |
|-------|-----|--------|
| Inference | http://127.0.0.1:8000/health | JSON `status: ok`, `emitter_connected: true` |
| Decision | http://127.0.0.1:8080/actuator/health | `{"status":"UP"}` |
| Presentation | http://127.0.0.1:5173/ | HTTP 200 |
| Media | `docker compose ps` | `sentinelvoice-asterisk` healthy |

Or: `.\make.cmd health`

### Demo path

1. Analyst Console → scenario **Deepfake CFO wire** → **Start**
2. Within ~20s the risk gauge should move from live STOMP telemetry (not hardcoded UI values)
3. Scenario linguistic / cross-channel fixtures show an amber **simulated signal** badge — that is intentional demo scaffolding, not live ASR/carrier data

### Individual planes

```bash
.\make.cmd asterisk   # docker compose up -d asterisk
.\make.cmd ml         # uvicorn :8000 (foreground)
.\make.cmd backend    # spring-boot:run :8080 (foreground)
.\make.cmd frontend   # vite :5173 strictPort (foreground)
```

### Quality

```bash
.\make.cmd test          # backend mvn test + ml pytest + frontend npm test
.\make.cmd eval          # ML benchmark (needs matplotlib in the venv)
.\make.cmd codec-study
.\make.cmd clean         # stop processes started by `dev`
```

### If you see `403 Invalid CORS request`

1. You are probably on **http://localhost:5174** because something else held 5173.
2. Stop the stray Vite process (`.\make.cmd clean`), free 5173, restart with `.\make.cmd frontend` or `.\make.cmd dev`.
3. Backend logs `cors_allowed_origins=...` at startup. Override with env  
   `SENTINELVOICE_CORS_ORIGINS=http://127.0.0.1:5173,http://localhost:5173` (comma-separated).

```bash
# Compose Media only (matches this repo's Dockerfiles)
docker compose up -d asterisk
```

Full documentation: [`docs/`](docs/) — start with [`docs/00_PROJECT_CONTEXT.md`](docs/00_PROJECT_CONTEXT.md).
