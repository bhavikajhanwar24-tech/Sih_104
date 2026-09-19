"""Fairness probe using real Common Voice TSV tags (Context §13.5).

Computes false-positive rate at a fixed operating point (pooled EER threshold from
a paired spoof-bearing set). Does **not** invent language / gender / age labels.

Usage::
  python -m benchmarks.fairness --datasets ../datasets
  python -m benchmarks.fairness --synthetic   # exits: no fabricated CV tags
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Optional

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from benchmarks.datasets import audio_for_item, load_dataset  # noqa: E402
from benchmarks.metrics import eer_and_threshold  # noqa: E402
from training.dataset import DEFAULT_DATASETS, corpora_present, fairness_tags  # noqa: E402
from training.features import STACK_DIM, TinyLCNN, lfcc_stack, pad_stack  # noqa: E402

SR = 16000
MAX_FRAMES = 200


def _load_model(ckpt: Path) -> Any:
    import torch

    blob = torch.load(ckpt, map_location="cpu", weights_only=False)
    model = TinyLCNN(int(blob.get("stack_dim", STACK_DIM)))
    model.load_state_dict(blob["state_dict"])
    model.eval()
    return model


def _score_items(model: Any, items: list) -> tuple[np.ndarray, np.ndarray]:
    import torch

    labels: list[int] = []
    scores: list[float] = []
    with torch.inference_mode():
        for item in items:
            pcm = audio_for_item(item, SR)
            stack = pad_stack(lfcc_stack(pcm, SR, max_frames=MAX_FRAMES), MAX_FRAMES)
            x = torch.from_numpy(stack).unsqueeze(0).unsqueeze(0)
            logit = float(model(x).item())
            labels.append(int(item.sample.label))
            scores.append(logit)
    return np.asarray(labels, dtype=np.int32), np.asarray(scores, dtype=np.float64)


def fpr_by_tag(
    labels: np.ndarray,
    scores: np.ndarray,
    tag_values: list[str],
    threshold: float,
) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for g in sorted(set(tag_values)):
        if g in ("-", "unspecified", ""):
            continue
        idx = [i for i, t in enumerate(tag_values) if t == g]
        y = labels[idx]
        s = scores[idx]
        bona = y == 0
        n_bona = int(np.sum(bona))
        if n_bona == 0:
            fpr = None
        else:
            fpr = float(np.sum(s[bona] >= threshold) / n_bona)
        out.append(
            {
                "group": g,
                "n": int(len(idx)),
                "n_bonafide": n_bona,
                "fpr": None if fpr is None else round(fpr, 6),
            }
        )
    return out


def run(args: argparse.Namespace) -> dict[str, Any]:
    if args.synthetic:
        payload = {
            "schema_version": 1,
            "status": "SYNTHETIC_SKIPPED",
            "source": "synthetic",
            "note": (
                "Fairness requires real Common Voice TSV tags. "
                "Refusing to fabricate language/gender/age groups under --synthetic."
            ),
            "groups": [],
            "by_dimension": {},
        }
        return payload

    present = corpora_present(args.datasets)
    cv = load_dataset(
        "common_voice_fairness",
        root=args.datasets,
        limit=args.limit,
        synthetic=False,
        channel_condition="clean_16k",
    )
    if cv.n == 0:
        return {
            "schema_version": 1,
            "status": "CORPUS_MISSING",
            "source": "missing",
            "presence": present,
            "note": "Common Voice (hi/mr/bn/ta/te) not found — see scripts/fetch_datasets.md",
            "groups": [],
            "by_dimension": {},
        }

    # Operating point from a spoof-bearing set (ASVspoof LA eval). Never tune for fairness.
    la = load_dataset(
        "asvspoof2019_la_eval",
        root=args.datasets,
        limit=args.limit,
        synthetic=False,
    )
    if la.n == 0:
        return {
            "schema_version": 1,
            "status": "CORPUS_MISSING",
            "source": "missing",
            "note": "Need ASVspoof 2019 LA eval to fix the operating-point threshold (not tuned for FPR parity).",
            "groups": [],
            "by_dimension": {},
        }

    ckpt = Path(args.checkpoint)
    if not ckpt.is_file():
        return {
            "schema_version": 1,
            "status": "CHECKPOINT_MISSING",
            "note": f"No checkpoint at {ckpt}",
            "groups": [],
            "by_dimension": {},
        }

    model = _load_model(ckpt)
    y_la, s_la = _score_items(model, la.items)
    _, thr = eer_and_threshold(y_la, s_la)

    y_cv, s_cv = _score_items(model, cv.items)
    # CV is bonafide-only: FPR = fraction of bona scored >= thr.
    lang_tags = [fairness_tags(it.sample).get("language_family", "unspecified") for it in cv.items]
    gender_tags = [fairness_tags(it.sample).get("gender", "unspecified") for it in cv.items]
    age_tags = [fairness_tags(it.sample).get("age", "unspecified") for it in cv.items]

    by_dim = {
        "language_family": fpr_by_tag(y_cv, s_cv, lang_tags, thr),
        "gender": fpr_by_tag(y_cv, s_cv, gender_tags, thr),
        "age": fpr_by_tag(y_cv, s_cv, age_tags, thr),
        "channel": [{"group": "clean_16k", "n": cv.n, "note": "CV scored clean; use run_eval for channel matrix"}],
    }
    # Flat groups for portal chart (language family preferred).
    groups = by_dim["language_family"] + by_dim["gender"] + by_dim["age"]

    return {
        "schema_version": 1,
        "status": "OK",
        "source": "disk",
        "metric": "false_positive_rate",
        "threshold_rule": "asvspoof2019_la_eval_pooled_eer_threshold",
        "threshold_logit": round(float(thr), 6),
        "checkpoint": str(ckpt),
        "n_common_voice": cv.n,
        "n_la_eval": la.n,
        "dataset_version": cv.version,
        "groups": groups,
        "by_dimension": by_dim,
        "note": (
            "FPR gaps reported as measured. Common Voice is bonafide-only; "
            "threshold fixed from ASVspoof LA eval EER (not tuned for parity)."
        ),
    }


def main(argv: Optional[list[str]] = None) -> int:
    p = argparse.ArgumentParser(description="Common Voice fairness FPR probe (§13.5)")
    p.add_argument("--datasets", type=Path, default=DEFAULT_DATASETS)
    p.add_argument("--checkpoint", type=Path, default=ROOT / "models" / "antispoof" / "codec_aug.pt")
    p.add_argument("--limit", type=int, default=None)
    p.add_argument("--synthetic", action="store_true")
    p.add_argument(
        "--out",
        type=Path,
        default=ROOT / "benchmarks" / "fairness_results.json",
    )
    args = p.parse_args(argv)
    payload = run(args)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(json.dumps(payload, indent=2))
    print(f"[fairness] wrote {args.out} status={payload.get('status')}")
    if payload.get("status") in ("CORPUS_MISSING", "CHECKPOINT_MISSING"):
        return 2
    if payload.get("status") == "SYNTHETIC_SKIPPED":
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
