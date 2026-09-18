# Demo scenarios (Context §14 / P11.1)

Six seeded fixtures that make every judge demo **reproducible with one click**.

| ID | Title | Expected final | Why it matters |
|---|---|---|---|
| `legit-cfo` | Legit CFO tax payment | L1 | Quiet on routine CXO traffic |
| `deepfake-ceo-wire` | Deepfake CFO wire (₹50L) | L4 | Full ladder |
| `liveness-challenge` | Amber Falcon 72 | L5 | Interactive defeat of RVC |
| `hinglish-grandparent` | Hinglish UPI bail scam | L4 | Senior Shield + code-switch |
| `false-positive-stress` | Genuine elderly, noisy line | **L2 only** | Corroboration gate — run this |
| `adversarial-evasion` | Room tone + breath + SNR | L4 | Context holds when acoustics drop |

Scenario 5 and 6 are **not optional** — they distinguish SentinelVoice from a raw detector.

## YAML schema

Each file defines:

- `id`, `title`, `description`, `teachingPoint`
- `expectedTrajectory[]` — `{tSec, risk, level, corroboration, confirmL5?}`
- `channelProfile` — `WEBRTC_WIDEBAND` | `VOIP_WIDEBAND` | `PSTN_NARROWBAND`
- `caller` / `callee` — CLI, trunk, claimed identity/role, employee ids
- `directoryOverrides[]`, `relationshipEdges[]`, `crossChannelEvents[]`
- `audio.source` — path under repo root, or use live mode
- `expectedFinalLevel` (+ optional `mustNotExceed` for scenario 5)

## One-click load

```http
GET  /api/v1/scenario
POST /api/v1/scenario/{id}/load?mode=replay
POST /api/v1/scenario/{id}/load?mode=live
```

`mode=replay` returns a session descriptor with the WAV path and ingest WebSocket URL.
`mode=live` returns the same session wired for Asterisk AudioSocket / softphone.

## Replay path (reliability)

From repo root, with the ml-engine venv active and a session already loaded:

```bash
python gateway/replay_audio.py \
  --wav scenarios/audio/02-deepfake-ceo-wire.wav \
  --session <sessionId> \
  --ws ws://127.0.0.1:8000/ingest/<sessionId>

# Force on-the-fly codec degrade (P4.3 fixture names)
python gateway/replay_audio.py --wav scenarios/audio/06-adversarial-evasion.wav \
  --session <sid> --profile pcm_mulaw_8k --loop
```

Frames are paced at **real time** (500 ms of audio every 500 ms wall-clock). Do not burn through the file — the gauge must evolve watchably.

## Live path (impact)

1. `POST .../load?mode=live`
2. Place / answer the SIP call through Asterisk (`gateway/asterisk_bridge.py`)
3. Analyst UI attaches to the returned `sessionId`

## Audio fixtures

Place 16 kHz mono PCM WAVs under `scenarios/audio/` matching the paths in each YAML.
If a file is missing, `replay_audio.py --generate-placeholder` will synthesise a short
speech-like tone so rehearsal still works.

```bash
python gateway/replay_audio.py --generate-placeholder --wav scenarios/audio/01-legit-cfo.wav
```

## Integration test

`ScenarioTrajectoryIT` loads every scenario, drives the intervention ladder along
`expectedTrajectory`, and asserts the final level (and `mustNotExceed` for scenario 5).
A silent regression that stops escalating correctly fails the build — not the stage.
