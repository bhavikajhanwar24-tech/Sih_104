# Anti-spoofing models shipped with SentinelVoice Inference Plane

## Active tier

**Tier 1 — LFCC + Tiny LCNN** (`ACTIVE_TIER = 1` in `app/modules/antispoof.py`)

- Front-end: 20 LFCC + delta + delta-delta (60-D stack), utterance CMVN
- Back-end: 3-layer LCNN → logit (spoof-positive)
- Calibration: Platt scaling `(A, B)` fit on **DEV only**, separately per
  `ChannelProfile` (`PSTN_NARROWBAND`, `VOIP_WIDEBAND`, `WEBRTC_WIDEBAND`)
- Live output: **calibrated** `spoofProbability ∈ [0,1]` (never a raw logit)

Tier 2 (wav2vec2 / WavLM / AASIST) is **not** shipped on the live path.

## What to train on

Primary training corpus (when present under repo-root / `ml-engine` `datasets/`):

| Corpus | Role |
|--------|------|
| ASVspoof 2019 LA train/dev/eval | In-domain train + eval |
| ASVspoof 2021 DF eval | Codec / compression stress |
| In-the-Wild (Müller et al.) | Real-world honesty check (§3.1) |

Smoke / CI: `python -m training.train_antispoof --synthetic --limit 200` builds a
tiny synthetic bonafide/spoof set. **Synthetic EER is not a publishable result.**

## Codec-augmented twin models (P12 table)

Always train **both**:

1. `baseline_no_codec_aug.pt` — no telephony round-trips
2. `codec_aug.pt` — ffmpeg mulaw/alaw/AMR-NB/Opus cache + noise / PLC / reverb / gain / pitch

Artifacts written to this directory:

- `metrics.json` — EER + min t-DCF before/after table
- `calibration.json` — per-profile Platt `(A, B)` + ECE
- `reliability_diagram.png` — primary (WEBRTC_WIDEBAND) reliability plot
- `reliability_*.png` — per-profile diagrams

## Measured numbers (fill after a real ASVspoof run)

| Condition | baseline EER | codec-aug EER | Notes |
|-----------|--------------|---------------|-------|
| ASVspoof 2019 LA eval | *run* | *run* | In-domain |
| ASVspoof 2021 DF | *run* | *run* | Codec stress |
| In-the-Wild | *run* | *run* | Expect much worse than in-domain (§3.1) |

Smoke-run placeholder metrics live in `metrics.json` after
`train_antispoof.py --synthetic --limit …`. Treat them as pipeline proof only.

## Inference budget

Target: **< 50 ms / 2 s window on CPU**. Logged at `antispoof.warmup()` as
`infer_latency_ms`. If a future Tier 2 exceeds this, quantise to ONNX int8 or
keep Tier 1 on the live path and use Tier 2 offline only.

## Known failure modes (honest)

1. **In-the-Wild collapse** — literature shows ~200–1000% relative EER degradation;
   we expect the same and design fusion so acoustic evidence alone cannot escalate
   (Context §3.1 / §9.3 corroboration gate).
2. **Channel mismatch** — models trained only on clean wideband under-detect on
   G.711; that is why codec-aug training and per-profile Platt exist.
3. **Speaker leakage** — LFCC-LCNN can latch onto speaker identity on small data;
   never claim identity-invariant detection without speaker-disjoint eval.
4. **Unseen generators** — a new vocoder / LLM-TTS unseen in train/dev will look
   closer to random; report condition-wise EER, never a single "accuracy".

## Reproduce

```bash
cd ml-engine
python -m training.train_antispoof --synthetic --limit 200 --epochs 3
python -m training.calibrate --checkpoint models/antispoof/codec_aug.pt --synthetic --limit 200
python -c "from app.modules import antispoof as a; print(a.warmup())"
```

For a real ASVspoof tree, drop corpora under `datasets/` (gitignored) and omit
`--synthetic`.
