# SentinelVoice Inference Plane (`ml-engine`)

Python 3.11+ FastAPI service that holds **all raw PCM** for SentinelVoice.

Raw PCM exists **only** in `RingBuffer`: fixed 8 s of float32, overwritten in place, never grown, never written to disk or logs. `close()` calls `zeroise()`. See Context §6.3.

## DSP modules (P4.1)

```bash
python -m scripts.inspect_features path/to.wav --profile narrowband
python -m scripts.inspect_features --compare bonafide.wav spoof.wav --profile wideband
```

Spectral (LFCC, STFT stats, dual-profile bands, CQT periodicity) and phase (IPD entropy, modified group delay) live under `app/modules/`. CQT runs every 4th window and is carried forward.

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
