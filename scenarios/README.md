# Scenarios (F18)

v1 YAML scenarios and fake FeatureFrame trajectory injection are **removed**.

Authoritative fixtures live in the Decision Plane classpath:

- `backend/src/main/resources/scenarios/*.json`

Audio WAVs (keep v1 files when present):

- `scenarios/audio/*.wav`

Run them from the UI at `/app/lab` (`LAB_MODE=true`) or via `/api/v2/lab/**`.
Replay tool: `gateway/replay_audio.py`.
