# SentinelVoice Inference Plane (`ml-engine`)

Python 3.11+ FastAPI service that holds **all raw PCM** for SentinelVoice.

Raw PCM exists **only** in `RingBuffer`: fixed 8 s of float32, overwritten in place, never grown, never written to disk or logs. `close()` calls `zeroise()`. See Context §6.3.

## Red-team / robustness evaluator (defensive only)

`app/modules/adversarial.py` and `POST/PUT /redteam/{sid}/*` are a **defensive testing tool**.
They perturb *existing* audio (noise, pitch/time warps, codec round-trip, reverb, breath-like
bursts, packet loss, band-limiting) so you can watch acoustic family scores degrade while
contextual families hold — Context §9.3 / §16.2.

**This is not an attack toolkit.** There is no voice-clone generation, RVC, TTS, or speaker
embedding transfer in this module. See `GET /redteam/capabilities` (`clone_generation: false`).

## DSP modules (P4.1–P4.2)

```bash
python -m scripts.inspect_features path/to.wav --profile narrowband
python -m scripts.inspect_features --compare bonafide.wav spoof.wav --profile wideband
```

- Spectral / phase: LFCC, STFT stats, dual-profile bands, CQT periodicity, IPD entropy
- Prosody / breath: Praat jitter/shimmer/HNR (librosa fallback), unnaturalness_score, breath detection
  (`SENTINELVOICE_ML_USE_PARSELMOUTH=false` forces the fallback)

## Run

```bash
cd ml-engine
pip install -e .
pytest
uvicorn app.main:app --reload --port 8000
```

- `GET /health` → `{status, version, active_sessions, emitter_connected, dropped_frames, sent_frames}`
- `POST /session/{sid}/open` creates a `PipelineSession` and starts the 500 ms FeatureFrame scheduler
- `POST /session/{sid}/close` zeroises the ring buffer and drops the session
- `WS /ingest/{sid}` JSON hello, then binary frames (`pcm_s16le`, `pcm_f32le`, `mulaw`, `alaw`, `slin16`). Ack every 10 frames.
- Stub FeatureFrames are pushed to Java at `ws://127.0.0.1:8080/ws/features` (P2.2). Real features arrive in P4.x.

Create the matching Decision Plane session first (`POST /api/v1/session/start`) or Java will drop unknown `sessionId`s.

Logs record lengths and counters only — never audio bytes.
