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

## Build scenario audio

WAVs under `scenarios/audio/` are **gitignored** (`*.wav`). Build them locally (or fetch a release asset) and keep hashes in `scenarios/audio/MANIFEST.json`.

### Layout

```
scenarios/audio/
  MANIFEST.json          # sha256 + licence + bona_fide|synthetic labels
  01-legit-cfo.wav
  02-deepfake-ceo-wire.wav
  …
  sources/               # YOU drop bona-fide inputs here
    01-legit-cfo.source.wav
    05-false-positive-stress.source.wav
    *.licence.txt        # optional licence notes (CC-0 for Common Voice)
  models/piper/          # Piper .onnx voices (not committed)
    en_US-lessac-medium.onnx
    en_US-lessac-medium.onnx.json
```

### Bona fide (scenarios 1 & 5)

Trim / peak-normalise only — **no silent-tone placeholders**. Missing sources fail the build.

1. Record a teammate (enrolled CFO-like speech for #1; elderly / noisy-line genuine speech for #5), **or**
2. Export Mozilla Common Voice clips (`en` / `hi`, CC-0) and rename to the expected source names above.

```bash
# Example Common Voice drop (after you download the corpus yourself):
cp /path/to/cv-en/clips/….wav scenarios/audio/sources/01-legit-cfo.source.wav
echo "Mozilla Common Voice en — CC-0" > scenarios/audio/sources/01-legit-cfo.source.wav.licence.txt
```

### Synthetic fixtures (scenarios 2, 3, 4) — Piper TTS

These are **test fixtures for our detector**, labelled `synthetic` in the manifest. They do **not** clone any real person's voice.

1. Install [Piper](https://github.com/rhasspy/piper/releases) and put the binary on `PATH`, or set `PIPER_BIN`.
2. Fetch a voice model (example: `en_US-lessac-medium`) into `scenarios/audio/models/piper/`:

```bash
# Linux/macOS example — adjust URLs to the current Piper voices release
mkdir -p scenarios/audio/models/piper
cd scenarios/audio/models/piper
# From https://github.com/rhasspy/piper/blob/master/VOICES.md
curl -LO https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx
curl -LO https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json
```

Scenario 4’s Piper script embeds Hinglish lexicon tokens (`turant`, `kisi ko mat batana`, `bail`, `musibat`) so ASR + lexicon paths are exercised.

### Scenario 6 — adversarial on clip 2 only

Built from `02-deepfake-ceo-wire.wav` via `ml-engine/app/modules/adversarial.py` (~20 dB SNR, room reverb, breath bursts, µ-law). No new generation capability.

### One command

```bash
# ml-engine venv active (needs numpy/scipy for scenario 6)
python scripts/build_scenario_audio.py
python scripts/build_scenario_audio.py --only 2,3,4,6   # TTS + adversarial only
```

If bona-fide sources are missing, the script **exits non-zero** and prints the expected filenames — it will not invent tones.

### Git LFS / release assets

Built WAVs are often tens of MB total. Prefer one of:

- **Git LFS** tracking `scenarios/audio/*.wav` (keep `MANIFEST.json` in normal git), or
- Attach a **GitHub Release** zip (`scenario-audio-YYYYMMDD.zip`) and document the tag in the PR; after download, verify `sha256` against `MANIFEST.json`.

Do not commit fabricated “passed demo” claims without `scripts/verify_scenarios.py` output.

## Verify (expected vs observed)

With backend + ml-engine up (and WAVs built):

```bash
python scripts/verify_scenarios.py
python scripts/verify_scenarios.py --only deepfake-ceo-wire,false-positive-stress
python scripts/verify_scenarios.py --json-out scenarios/audio/last-verify.json
```

The script:

1. Loads each scenario (`autoReplay=false`)
2. Replays the WAV in **real time** via `gateway/replay_audio.py`
3. Polls `/api/v1/session/{id}/telemetry/latest`
4. Prints a table: scenario, expected level, observed level, time-to-L4, ARI hold status, pass/fail

On fail it dumps family scores — **no fusion threshold/weight edits**.

**ARI hold soft-skip:** WAV replay has no Asterisk ConfBridge. If ARI is down or there is no live call, physical hold is reported as `SOFT_SKIP` while Decision Plane L4 is still scored. That matches `docs/KNOWN_LIMITATIONS.md`.

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

> Prefer `scripts/build_scenario_audio.py` over `replay_audio.py --generate-placeholder`. Placeholders are speech-like tones for emergency rehearsal only; they do **not** exercise ASR / lexicon / bona-fide paths.

## Live path (impact)

1. `POST .../load?mode=live`
2. Place / answer the SIP call through Asterisk (`gateway/asterisk_bridge.py`)
3. Analyst UI attaches to the returned `sessionId`

## Integration test

`ScenarioTrajectoryIT` loads every scenario, drives the intervention ladder along
`expectedTrajectory`, and asserts the final level (and `mustNotExceed` for scenario 5).
A silent regression that stops escalating correctly fails the build — not the stage.
