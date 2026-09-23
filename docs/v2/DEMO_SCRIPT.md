# SentinelVoice V2 — 7-minute demo storyboard

Audience: judges / evaluators. Stack: Decision Plane `:8081`, Frontend `:5173`,
ml-engine, Asterisk (optional). Seed first if the DB is empty.

## Pre-flight (90 seconds, off-stage)

1. Profiles: `SPRING_PROFILES_ACTIVE=demo` (or `dev`), `LAB_MODE=true`.
2. Seed: `POST /internal/v2/demo/seed?tenant=all` (ML_SERVICE / internal token).
3. Softphones registered for extensions **1001–1004** (Demo Bank), or skip to
   Lab WAV-only path.
4. Login ready: `admin@demo-bank.demo` / `DemoPassw0rd!`.

---

## Minute 0–1 — Tenant & directory

| You click / say | Judges see |
|-----------------|------------|
| Login as Demo Bank admin | Multi-tenant shell, not a single hackathon page |
| Open **Directory** | ~25 employees, authority limits, CFO Priya + clerk Rahul |
| Mention “second tenant Demo Insurer has different policies” | Isolation is real |

**Say:** “Same product, per-tenant policy and response plans — not a hard-coded demo.”

---

## Minute 1–2 — Policies & response

| You click | Judges see |
|-----------|------------|
| **Policies** → ACTIVE set | Rules with origin **DEMO_FIXTURE** (or demo badge) |
| Sample docs titles | Payments Authorisation + Phone Verification SOP |
| **Response Plans** | L1–L4 actions populated |

**Say:** “LLM never decides levels — approved config does.”

---

## Minute 2–4 — Lab: legit vs deepfake

| You click | Judges see |
|-----------|------------|
| **Lab** (`/app/lab`) | **SIMULATED** badge (only here) |
| Select **Legit CFO on known mobile** → **Run** | Session appears on Live Calls; stays L0/L1 |
| Select **Deepfake CFO wire** → **Run** | Climbs toward L3/L4; expected vs actual panel |

**Say:** “Audio is real WAV through the pipeline — not canned FeatureFrames.”

If ARI is down: replay still feeds ml-engine; softphones optional.

---

## Minute 4–5 — Live interdiction (optional softphone)

| You do | Judges see |
|--------|------------|
| Answer on extension 1002 while deepfake runs | Hold / advisory per response plan |
| Open Live Call detail | Reasons, ticks, no raw transcript |

Skip if no softphones — stay on Lab compare panel.

---

## Minute 5–6 — Second tenant contrast

| You click | Judges see |
|-----------|------------|
| Logout → `admin@demo-insurer.demo` | Different org name |
| Policies / Response | Claims-oriented rules; different L3/L4 actions |
| (Optional) Lab run same deepfake script | Different interdiction behaviour |

---

## Minute 6–7 — Governance close

| You click | Judges see |
|-----------|------------|
| **Audit** | Hash-chained events for seed/session actions |
| **Analytics** (if labels exist) | Metrics without voice-inferred demographics |
| Reset mention | `POST /internal/v2/demo/reset` wipes **only** `is_demo` tenants |

**Close:** “Production profile refuses seed. Demo flag protects real tenants.”

---

## Fallbacks — if X fails, do Y

| Failure | Fallback |
|---------|----------|
| LLM / Ollama offline | Settings show degraded; policy runtime still uses ACTIVE rules (`CONTINUE_RULES_ONLY`). Lab WAV path does not need LLM for acoustic climb demo. |
| No Wi-Fi / no public SIP | Loopback softphones (`127.0.0.1`) or skip softphones — Lab **WAV replay** into `ws://localhost:8000/ingest/{session}` still drives Live Calls. |
| Asterisk / ARI down | Run still starts session + WAV replay; UI note says ARI unavailable. |
| Missing WAV under `scenarios/audio/` | `replay_audio.py --generate-placeholder` creates a timed tone (not speech) — still exercises ingest. Prefer keeping v1 WAVs if present. |
| Seed 403 | Wrong profile — switch off `prod`; confirm `DemoProfileGuard`. |
| Lab 403 `LAB_MODE_OFF` | Set `LAB_MODE=true` and restart Decision Plane. |
| Login MFA friction | Demo users seed with `mfa_enabled=FALSE`. |
| Frontend “Simulated” elsewhere | Bug — badge must appear **only** on `/app/lab`. |

## Quick curl cheat-sheet

```bash
# seed (replace token from .env ML_SERVICE_TOKEN)
curl -X POST "http://localhost:8081/internal/v2/demo/seed?tenant=all" \
  -H "X-ML-Service-Token: $ML_SERVICE_TOKEN"

# reset demo tenants only
curl -X POST "http://localhost:8081/internal/v2/demo/reset" \
  -H "X-ML-Service-Token: $ML_SERVICE_TOKEN"
```
