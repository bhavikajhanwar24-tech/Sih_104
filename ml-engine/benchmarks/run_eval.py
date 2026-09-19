#!/usr/bin/env python3
"""Run anti-spoof evaluation across datasets × channel conditions (Context §15).

Writes:
  docs/EVALUATION_REPORT.md
  ml-engine/benchmarks/results.json
  docs/eval_plots/reliability_*.png

Usage::
  python -m benchmarks.run_eval --limit 80 --synthetic --seed 42
  make eval
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any, Optional

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from benchmarks.datasets import (  # noqa: E402
    DATASET_IDS,
    ChannelCondition,
    DatasetBundle,
    audio_for_item,
    bundle_manifest,
    iter_eval_matrix,
    load_dataset,
)
from benchmarks.metrics import (  # noqa: E402
    latency_summary,
    reliability_diagram,
    summarise_detection,
)
from benchmarks.report import (  # noqa: E402
    build_meta,
    write_markdown_report,
    write_results_json,
)
from training.dataset import DEFAULT_DATASETS  # noqa: E402
from training.features import STACK_DIM, TinyLCNN, lfcc_stack, pad_stack  # noqa: E402

SEED_DEFAULT = 42
MAX_FRAMES = 200
SR = 16000

CHANNEL_LIST: list[ChannelCondition] = [
    "clean_16k",
    "opus_24k",
    "g711_ulaw_8k",
    "amr_nb_12k2",
]


def _set_seed(seed: int) -> None:
    np.random.seed(seed)
    try:
        import torch

        torch.manual_seed(seed)
    except ImportError:
        pass


def _discover_checkpoints(models_dir: Path) -> list[Path]:
    preferred = [
        models_dir / "baseline_no_codec_aug.pt",
        models_dir / "codec_aug.pt",
    ]
    found = [p for p in preferred if p.is_file()]
    if found:
        return found
    return sorted(models_dir.glob("*.pt"))


def _ensure_checkpoints(
    models_dir: Path,
    *,
    datasets_root: Path,
    limit: Optional[int],
    synthetic: bool,
    epochs: int,
) -> list[Path]:
    found = _discover_checkpoints(models_dir)
    if found:
        return found
    if not synthetic:
        print(
            "[eval] no checkpoints and --synthetic not set — "
            "run training.train_antispoof on disk corpora first "
            "(see scripts/fetch_datasets.md).",
            file=sys.stderr,
        )
        return []
    print("[eval] no checkpoints — training synthetic smoke models…")
    from training.train_antispoof import build_eval_map, pick_device, train_one
    from training.dataset import resolve_splits
    import torch

    lim = limit or 60
    splits = resolve_splits(datasets_root, limit=lim, synthetic=True)
    train_s = splits["train"]
    dev_s = splits["dev"]
    eval_map = build_eval_map(splits)
    device = pick_device("auto")
    models_dir.mkdir(parents=True, exist_ok=True)
    cache_dir = datasets_root / "_codec_cache"
    # Smoke auto-train: skip ffmpeg codec round-trips (slow / flaky). For the real
    # §15.3 before/after table, run `python -m training.train_antispoof` with corpora.
    for i, name in enumerate(("baseline_no_codec_aug", "codec_aug")):
        torch.manual_seed(42 + i)
        train_one(
            name=name,
            train_samples=train_s,
            dev_samples=dev_s,
            eval_map=eval_map,
            out_dir=models_dir,
            epochs=max(1, epochs),
            batch_size=16,
            lr=1e-3,
            use_codec_aug=False,
            aug_p=0.0,
            cache_dir=cache_dir,
            device=device,
        )
    return _discover_checkpoints(models_dir)


def _load_model(ckpt: Path) -> tuple[Any, str, dict[str, dict[str, float]]]:
    import torch

    blob = torch.load(ckpt, map_location="cpu", weights_only=False)
    model = TinyLCNN(int(blob.get("stack_dim", STACK_DIM)))
    model.load_state_dict(blob["state_dict"])
    model.eval()
    model_id = str(blob.get("model_id", f"lfcc-lcnn-tier1/{ckpt.stem}"))
    cal_path = ckpt.parent / "calibration.json"
    calibration: dict[str, dict[str, float]] = {}
    if cal_path.is_file():
        cal = json.loads(cal_path.read_text(encoding="utf-8"))
        for prof, params in (cal.get("profiles") or {}).items():
            calibration[prof] = {"A": float(params["A"]), "B": float(params["B"])}
    return model, model_id, calibration


def _sigmoid(x: float) -> float:
    x = float(np.clip(x, -40, 40))
    return float(1.0 / (1.0 + np.exp(-x)))


def _platt(logit: float, calibration: dict[str, dict[str, float]]) -> float:
    params = calibration.get("webrtc_wideband") or next(iter(calibration.values()), None)
    if not params:
        return _sigmoid(logit)
    return _sigmoid(float(params["A"]) * logit + float(params["B"]))


def score_bundle(
    model: Any,
    bundle: DatasetBundle,
    calibration: dict[str, dict[str, float]],
) -> tuple[np.ndarray, np.ndarray, np.ndarray, list[str], list[float]]:
    """Return labels, logits, probs, fairness groups, per-item latency_ms."""
    import torch

    labels: list[int] = []
    logits: list[float] = []
    probs: list[float] = []
    groups: list[str] = []
    lat_ms: list[float] = []
    model.eval()
    with torch.inference_mode():
        for item in bundle.items:
            pcm = audio_for_item(item, SR)
            t0 = time.perf_counter()
            stack = pad_stack(lfcc_stack(pcm, SR, max_frames=MAX_FRAMES), MAX_FRAMES)
            x = torch.from_numpy(stack).unsqueeze(0).unsqueeze(0)
            logit = float(model(x).item())
            dt = (time.perf_counter() - t0) * 1000.0
            labels.append(int(item.sample.label))
            logits.append(logit)
            probs.append(_platt(logit, calibration))
            groups.append(item.fairness_group)
            lat_ms.append(dt)
    return (
        np.asarray(labels, dtype=np.int32),
        np.asarray(logits, dtype=np.float64),
        np.asarray(probs, dtype=np.float64),
        groups,
        lat_ms,
    )


def fairness_table(
    labels: np.ndarray,
    scores: np.ndarray,
    groups: list[str],
    threshold: float,
    *,
    tag_notes: Optional[list[str]] = None,
) -> list[dict[str, Any]]:
    """FPR by fairness group at a fixed threshold (EER θ of the pooled set).

    Only emits groups that have real metadata (not ``unspecified``). Does not
    invent language/gender/age labels.
    """
    out: list[dict[str, Any]] = []
    for g in sorted(set(groups)):
        if g in ("unspecified", "-", ""):
            continue
        idx = [i for i, gg in enumerate(groups) if gg == g]
        y = labels[idx]
        s = scores[idx]
        bona = y == 0
        n_bona = int(np.sum(bona))
        if n_bona == 0:
            fpr = None
        else:
            fpr = float(np.sum((s[bona] >= threshold)) / n_bona)
        note = "from_sample_metadata"
        if tag_notes:
            notes = {tag_notes[i] for i in idx if i < len(tag_notes)}
            if notes:
                note = sorted(notes)[0]
        out.append(
            {
                "group": g,
                "n": int(len(idx)),
                "n_bonafide": n_bona,
                "fpr": None if fpr is None else round(fpr, 6),
                "note": note,
            }
        )
    return out


def measure_latency(
    model: Any,
    calibration: dict[str, dict[str, float]],
    *,
    n: int = 40,
) -> dict[str, Any]:
    """p50/p95/p99 for fast (antispoof), slow (ASR-sized FFT stub), end-to-end."""
    import torch

    rng = np.random.default_rng(0)
    fast: list[float] = []
    slow: list[float] = []
    e2e: list[float] = []
    model.eval()
    with torch.inference_mode():
        for _ in range(n):
            pcm = (0.05 * rng.standard_normal(SR * 2)).astype(np.float32)
            t0 = time.perf_counter()
            stack = pad_stack(lfcc_stack(pcm, SR, max_frames=MAX_FRAMES), MAX_FRAMES)
            x = torch.from_numpy(stack).unsqueeze(0).unsqueeze(0)
            logit = float(model(x).item())
            _ = _platt(logit, calibration)
            t1 = time.perf_counter()
            # Slow-path stand-in: 6 s window magnitude FFT (Whisper may be absent).
            long_pcm = (0.05 * rng.standard_normal(SR * 6)).astype(np.float32)
            _ = np.abs(np.fft.rfft(long_pcm[: SR * 2]))
            time.sleep(0.002)  # tiny scheduler overhead analogue
            t2 = time.perf_counter()
            fast.append((t1 - t0) * 1000.0)
            slow.append((t2 - t1) * 1000.0)
            e2e.append((t2 - t0) * 1000.0)
    return {
        "fast_path": latency_summary(fast),
        "slow_path": latency_summary(slow),
        "end_to_end": latency_summary(e2e),
        "note": (
            "fast_path = LFCC-LCNN antispoof window; "
            "slow_path = FFT stub (+2 ms) when Whisper is not loaded in this harness; "
            "replace with live ASR timings in production diagnostics."
        ),
    }


def run(args: argparse.Namespace) -> dict[str, Any]:
    _set_seed(args.seed)
    datasets_root = Path(args.datasets)
    models_dir = Path(args.models)
    out_md = Path(args.report)
    out_json = Path(args.results_json)
    plots_dir = Path(args.plots_dir)

    use_synthetic = bool(args.synthetic)
    from training.dataset import corpora_present

    present = corpora_present(datasets_root)
    if not use_synthetic:
        probe = load_dataset("asvspoof2019_la_eval", root=datasets_root, limit=2, synthetic=False)
        if probe.n == 0:
            print(
                "[eval] STOP: ASVspoof 2019 LA eval missing under "
                f"{datasets_root}. Leaving existing report cells unchanged "
                "(do not invent source=disk numbers). Place corpora "
                "(scripts/fetch_datasets.md) or re-run with --synthetic for a "
                f"labelled smoke run.\n  presence={present}",
                file=sys.stderr,
            )
            raise SystemExit(2)

    ckpts = _ensure_checkpoints(
        models_dir,
        datasets_root=datasets_root,
        limit=args.limit,
        synthetic=use_synthetic,
        epochs=args.train_epochs,
    )
    if not ckpts:
        raise SystemExit("No model checkpoints available and training failed.")

    models_meta: list[dict[str, Any]] = []
    dataset_meta: dict[str, dict[str, Any]] = {}
    cells: list[dict[str, Any]] = []
    plots: list[str] = []
    fairness_groups_all: list[dict[str, Any]] = []
    latency_block: dict[str, Any] = {}

    # Exclude fairness-only CV id from the channel matrix; scored separately below.
    default_ids = [d for d in DATASET_IDS if d != "common_voice_fairness"]
    ds_ids = list(args.dataset) if args.dataset else default_ids
    channels: list[ChannelCondition] = list(args.channel) if args.channel else list(CHANNEL_LIST)

    for ckpt in ckpts:
        model, model_id, calibration = _load_model(ckpt)
        cal_path = ckpt.parent / "calibration.json"
        try:
            ckpt_disp = ckpt.resolve().relative_to(REPO.resolve()).as_posix()
        except ValueError:
            ckpt_disp = ckpt.as_posix()
        models_meta.append(
            {
                "model_id": model_id,
                "checkpoint": ckpt_disp,
                "calibration": cal_path.as_posix() if cal_path.is_file() else "sigmoid-fallback",
            }
        )
        print(f"[eval] model={model_id}")

        # Fairness probe: prefer Common Voice real tags; else ASVspoof metadata only
        # (usually unspecified — never invent proxy language/gender/age).
        fair_labels: list[int] = []
        fair_scores: list[float] = []
        fair_groups: list[str] = []

        if not use_synthetic:
            cv_bundle = load_dataset(
                "common_voice_fairness",
                root=datasets_root,
                limit=args.limit,
                synthetic=False,
                channel_condition="clean_16k",
            )
            if cv_bundle.n > 0:
                print(f"  fairness Common Voice n={cv_bundle.n} source={cv_bundle.source}")
                # Need a spoof-bearing set for the threshold; score LA clean for θ.
                la_for_thr = load_dataset(
                    "asvspoof2019_la_eval",
                    root=datasets_root,
                    limit=args.limit,
                    synthetic=False,
                    channel_condition="clean_16k",
                )
                if la_for_thr.n > 0:
                    y_la, s_la, _, _, _ = score_bundle(model, la_for_thr, calibration)
                    from benchmarks.metrics import eer_and_threshold

                    _, thr = eer_and_threshold(y_la, s_la)
                    y_cv, s_cv, _, groups_cv, _ = score_bundle(model, cv_bundle, calibration)
                    fairness_groups_all = fairness_table(y_cv, s_cv, groups_cv, thr)
                    dataset_meta["common_voice_fairness:clean_16k"] = bundle_manifest(cv_bundle)

        for bundle in iter_eval_matrix(
            root=datasets_root,
            limit=args.limit,
            synthetic=use_synthetic,
            datasets=ds_ids,
            channels=channels,
        ):
            if bundle.n == 0:
                print(
                    f"  skip empty {bundle.dataset_id} "
                    f"(source={bundle.source}) — {bundle.note or 'missing'}"
                )
                continue
            ch = bundle.items[0].channel_condition
            print(f"  {bundle.dataset_id} × {ch} n={bundle.n} source={bundle.source}")
            labels, logits, probs, groups, _lat = score_bundle(model, bundle, calibration)
            metrics = summarise_detection(labels, logits, probs)
            cell = {
                "model_id": model_id,
                "dataset_id": bundle.dataset_id,
                "channel_condition": ch,
                "dataset_version": bundle.version,
                "source": bundle.source,
                "note": bundle.note,
                "metrics": metrics,
            }
            cells.append(cell)
            man = bundle_manifest(bundle)
            dataset_meta[f"{bundle.dataset_id}:{ch}"] = man

            # Reliability diagram for clean + ITW cells (primary honesty surfaces).
            if ch == "clean_16k" or bundle.dataset_id == "in_the_wild":
                stem = f"reliability_{ckpt.stem}_{bundle.dataset_id}_{ch}"
                png = plots_dir / f"{stem}.png"
                reliability_diagram(
                    probs,
                    labels,
                    png,
                    title=f"{model_id} | {bundle.dataset_id} | {ch}",
                )
                # Path relative to docs/ for markdown embedding.
                try:
                    rel = png.resolve().relative_to(out_md.resolve().parent)
                    plots.append(rel.as_posix())
                except ValueError:
                    plots.append(png.as_posix())

            if not fairness_groups_all:
                fair_labels.extend(int(x) for x in labels.tolist())
                fair_scores.extend(float(x) for x in logits.tolist())
                fair_groups.extend(groups)

        if not fairness_groups_all and fair_labels:
            y = np.asarray(fair_labels, dtype=np.int32)
            s = np.asarray(fair_scores, dtype=np.float64)
            from benchmarks.metrics import eer_and_threshold

            _, thr = eer_and_threshold(y, s)
            fairness_groups_all = fairness_table(y, s, fair_groups, thr)

        if not latency_block:
            latency_block = measure_latency(model, calibration, n=min(40, max(10, (args.limit or 40) // 2)))

    payload: dict[str, Any] = {
        "schema_version": 1,
        "meta": build_meta(repo=REPO, seed=args.seed, synthetic=use_synthetic, limit=args.limit),
        "models": models_meta,
        "datasets": list({v["dataset_id"]: v for v in dataset_meta.values()}.values()),
        "cells": cells,
        "fairness": {
            "metric": "false_positive_rate",
            "threshold_rule": "pooled_eer_threshold",
            "groups": fairness_groups_all,
            "note": (
                "Groups come from Common Voice TSV language/gender/age tags when present. "
                "Empty groups mean metadata was absent — no proxy rotation / fabricated FPR. "
                "See also `python -m benchmarks.fairness`."
                if not use_synthetic
                else "Synthetic smoke: fairness groups omitted (would require fabricated CV tags)."
            ),
        },
        "latency": latency_block,
        "plots": plots,
        "honesty": {
            "in_the_wild": (
                "In-the-Wild EER is expected to be much higher than ASVspoof in-domain "
                "(Context §3.1). Report the measured number without smoothing."
            ),
            "synthetic": (
                "Synthetic cells are smoke-test only and must not be cited as published results."
                if use_synthetic
                else None
            ),
            "corpora_presence": present,
        },
    }

    write_results_json(payload, out_json)
    write_markdown_report(payload, out_md)
    print(f"[eval] wrote {out_md}")
    print(f"[eval] wrote {out_json}")
    return payload


def main() -> int:
    p = argparse.ArgumentParser(description="SentinelVoice §15 evaluation harness")
    p.add_argument("--datasets", type=Path, default=DEFAULT_DATASETS)
    p.add_argument("--models", type=Path, default=ROOT / "models" / "antispoof")
    p.add_argument("--report", type=Path, default=REPO / "docs" / "EVALUATION_REPORT.md")
    p.add_argument("--results-json", type=Path, default=ROOT / "benchmarks" / "results.json")
    p.add_argument("--plots-dir", type=Path, default=REPO / "docs" / "eval_plots")
    p.add_argument("--limit", type=int, default=None, help="Cap samples per dataset (smoke)")
    p.add_argument("--synthetic", action="store_true", help="Force synthetic smoke corpora")
    p.add_argument("--seed", type=int, default=SEED_DEFAULT)
    p.add_argument("--train-epochs", type=int, default=2, help="Epochs if auto-training missing ckpts")
    p.add_argument("--dataset", action="append", default=None, help="Restrict dataset id (repeatable)")
    p.add_argument("--channel", action="append", default=None, help="Restrict channel condition")
    args = p.parse_args()
    run(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
