# Evaluation harness (Context §15)

Produces honest anti-spoof metrics across datasets and telephony channel profiles.

## Outputs

| Artifact | Path |
|---|---|
| Markdown report | `docs/EVALUATION_REPORT.md` |
| Machine-readable results (compliance portal) | `ml-engine/benchmarks/results.json` |
| Reliability diagrams | `docs/eval_plots/reliability_*.png` |

## Metrics

- **EER** + exact threshold
- **min t-DCF** — ASVspoof 2019 CM formulation (Todisco et al., Interspeech 2019; `C_miss=1`, `C_fa=10`, `π_spoof=0.05`)
- **FPR @ TPR** 0.80 / 0.90 / 0.95
- **AUC**, **ECE**, reliability diagrams
- **Fairness**: per-group FPR (§13.5)
- **Latency**: p50 / p95 / p99 for fast path, slow path, end-to-end

## Quick start

```bash
# From repo root (Git Bash / WSL / make):
make eval

# Windows (PowerShell) — same command the Makefile runs:
cd ml-engine
python -m benchmarks.run_eval --limit 40 --synthetic --seed 42 --train-epochs 2
```

Place real corpora under `datasets/` (see `training/dataset.py`). When present, omit `--synthetic` and the report labels cells as `disk`.

Checkpoints are loaded from `ml-engine/models/antispoof/`:

- `baseline_no_codec_aug.pt`
- `codec_aug.pt`

If missing, the harness auto-trains short smoke checkpoints (not for publication).

## Honesty

In-the-Wild EER in the mid-20s (%) is an expected field result (§3.1), not a failure of the report. Do not tune thresholds on the eval set. Synthetic cells are labelled and must not be cited as published numbers.
