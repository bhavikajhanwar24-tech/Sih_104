# Evaluation harness (Context §15)

Produces honest anti-spoof metrics across datasets and telephony channel profiles.

## Outputs

| Artifact | Path |
|---|---|
| Markdown report | `docs/EVALUATION_REPORT.md` |
| Machine-readable results (compliance portal) | `ml-engine/benchmarks/results.json` |
| Reliability diagrams | `docs/eval_plots/reliability_*.png` |
| **Codec robustness study** | `docs/CODEC_ROBUSTNESS_STUDY.md` |
| Codec study chart | `docs/eval_plots/codec_robustness_bars.png` |
| Codec study JSON | `ml-engine/benchmarks/codec_study_results.json` |

## Metrics

- **EER** + exact threshold
- **min t-DCF** — ASVspoof 2019 CM formulation (Todisco et al., Interspeech 2019)
- **FPR @ TPR** 0.80 / 0.90 / 0.95
- **AUC**, **ECE**, reliability diagrams
- **Fairness**: per-group FPR (§13.5)
- **Latency**: p50 / p95 / p99 for fast path, slow path, end-to-end

## Quick start

```bash
# From repo root:
make eval

# Windows (PowerShell):
cd ml-engine
python -m benchmarks.run_eval --limit 40 --synthetic --seed 42 --train-epochs 2
```

### Codec robustness study (§15.3 / P12.2)

```bash
make codec-study
# or:
cd ml-engine
python -m benchmarks.codec_study --limit 40 --synthetic --epochs 4 --seed 42 --retrain
```

`build_codec_set.py` caches ffmpeg round-trips under `datasets/codec_study_cache/`.

Checkpoints: `ml-engine/models/antispoof/baseline_no_codec_aug.pt` and `codec_aug.pt`.
If `codec_aug` was trained without augmentation, the study retrains both.

## Honesty

In-the-Wild EER in the mid-20s (%) is an expected field result (§3.1). Synthetic cells are labelled. ffmpeg ≠ a real carrier path — see limitations in `docs/CODEC_ROBUSTNESS_STUDY.md`.
