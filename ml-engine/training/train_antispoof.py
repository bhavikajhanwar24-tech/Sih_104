#!/usr/bin/env python3
"""Train Tier-1 LFCC-LCNN anti-spoof models (with and without codec augmentation).

Writes checkpoints + metrics.json with EER / min t-DCF per eval condition.

Usage (smoke)::
  python -m training.train_antispoof --limit 200 --synthetic --epochs 3

Usage (disk / GPU)::
  python -m training.train_antispoof --datasets ../datasets --epochs 20 --resume

Speaker-disjoint: ASVspoof 2019 LA protocols are used as-is; ``--limit`` preserves
speaker disjointness via ``training.dataset.resolve_splits``.

DO NOT report train accuracy as general performance.
DO NOT tune thresholds on the evaluation set — only EER / min t-DCF.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any, Optional

import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import DataLoader, Dataset

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from training.augment import Augmentor, prewarm_codec_cache  # noqa: E402
from training.dataset import (  # noqa: E402
    DEFAULT_DATASETS,
    Sample,
    assert_speaker_disjoint,
    corpora_present,
    load_audio,
    primary_train_ready,
    resolve_splits,
)
from training.features import STACK_DIM, TinyLCNN, lfcc_stack, pad_stack  # noqa: E402
from training.metrics import eer_and_threshold, min_tdcf  # noqa: E402

SR = 16000
MODEL_ID_BASE = "lfcc-lcnn-tier1"
MAX_FRAMES = 200


def pick_device(prefer: str = "auto") -> torch.device:
    if prefer == "cpu":
        return torch.device("cpu")
    if prefer == "cuda":
        if not torch.cuda.is_available():
            raise RuntimeError("--device cuda requested but CUDA is not available")
        return torch.device("cuda")
    return torch.device("cuda" if torch.cuda.is_available() else "cpu")


class SpoofDataset(Dataset):
    def __init__(
        self,
        samples: list[Sample],
        *,
        augmentor: Optional[Augmentor] = None,
    ) -> None:
        self.samples = samples
        self.augmentor = augmentor

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, idx: int) -> tuple[torch.Tensor, torch.Tensor]:
        s = self.samples[idx]
        audio = load_audio(s.path, SR)
        if self.augmentor is not None:
            audio = self.augmentor(audio, SR, src_path=s.path)
        stack = pad_stack(lfcc_stack(audio, SR, max_frames=MAX_FRAMES), MAX_FRAMES)
        x = torch.from_numpy(stack).unsqueeze(0)  # (1, F, T)
        y = torch.tensor(float(s.label), dtype=torch.float32)
        return x, y


def _collate(batch: list[tuple[torch.Tensor, torch.Tensor]]) -> tuple[torch.Tensor, torch.Tensor]:
    xs = torch.stack([b[0] for b in batch], dim=0)
    ys = torch.stack([b[1] for b in batch], dim=0)
    return xs, ys


@torch.inference_mode()
def collect_scores(
    model: TinyLCNN,
    samples: list[Sample],
    device: torch.device,
) -> tuple[np.ndarray, np.ndarray]:
    model.eval()
    labels: list[int] = []
    scores: list[float] = []
    for s in samples:
        audio = load_audio(s.path, SR)
        stack = pad_stack(lfcc_stack(audio, SR, max_frames=MAX_FRAMES), MAX_FRAMES)
        x = torch.from_numpy(stack).unsqueeze(0).unsqueeze(0).to(device)
        logit = float(model(x).cpu().item())
        scores.append(logit)
        labels.append(int(s.label))
    return np.asarray(labels, dtype=np.int32), np.asarray(scores, dtype=np.float64)


def evaluate_split(model: TinyLCNN, samples: list[Sample], device: torch.device, name: str) -> dict[str, Any]:
    if not samples:
        return {"name": name, "n": 0, "eer": None, "min_tdcf": None, "note": "missing_corpus"}
    labels, scores = collect_scores(model, samples, device)
    eer, thr = eer_and_threshold(labels, scores)
    tdcf = min_tdcf(labels, scores)
    return {
        "name": name,
        "n": int(labels.size),
        "n_bonafide": int(np.sum(labels == 0)),
        "n_spoof": int(np.sum(labels == 1)),
        "eer": round(float(eer), 4),
        "eer_threshold_logit": round(float(thr), 4),
        "min_tdcf": round(float(tdcf), 4),
    }


def train_one(
    *,
    name: str,
    train_samples: list[Sample],
    dev_samples: list[Sample],
    eval_map: dict[str, list[Sample]],
    out_dir: Path,
    epochs: int,
    batch_size: int,
    lr: float,
    use_codec_aug: bool,
    aug_p: float,
    cache_dir: Path,
    device: torch.device,
    resume: bool = False,
    start_epoch: int = 1,
) -> dict[str, Any]:
    """Train one checkpoint. Shared by CLI, Colab notebook, and harness auto-train."""
    out_dir.mkdir(parents=True, exist_ok=True)
    ckpt_path = out_dir / f"{name}.pt"
    augmentor = None
    if use_codec_aug:
        print(f"[{name}] prewarming codec cache…")
        prewarm_codec_cache([s.path for s in train_samples], cache_dir, limit=len(train_samples))
        augmentor = Augmentor(p=aug_p, use_codec=True, cache_dir=cache_dir, seed=42)

    ds = SpoofDataset(train_samples, augmentor=augmentor)
    loader = DataLoader(ds, batch_size=batch_size, shuffle=True, collate_fn=_collate, num_workers=0)
    model = TinyLCNN(STACK_DIM).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    crit = nn.BCEWithLogitsLoss()
    epoch0 = start_epoch

    if resume and ckpt_path.is_file():
        blob = torch.load(ckpt_path, map_location=device, weights_only=False)
        model.load_state_dict(blob["state_dict"])
        if "optimizer" in blob:
            opt.load_state_dict(blob["optimizer"])
        epoch0 = int(blob.get("epoch", 0)) + 1
        print(f"[{name}] resumed from {ckpt_path} at epoch {epoch0}")

    t0 = time.perf_counter()
    last_epoch = epoch0 - 1
    for epoch in range(epoch0, epochs + 1):
        model.train()
        total_loss = 0.0
        n = 0
        for x, y in loader:
            x = x.to(device)
            y = y.to(device)
            opt.zero_grad(set_to_none=True)
            logits = model(x)
            loss = crit(logits, y)
            loss.backward()
            opt.step()
            total_loss += float(loss.item()) * y.size(0)
            n += int(y.size(0))
        last_epoch = epoch
        dev_metrics = evaluate_split(model, dev_samples, device, "dev")
        print(
            f"[{name}] epoch {epoch}/{epochs} loss={total_loss / max(n, 1):.4f} "
            f"dev_eer={dev_metrics.get('eer')} device={device}"
        )
        # Periodic resume-friendly save (DEV metrics only — never tune on eval).
        torch.save(
            {
                "model_id": f"{MODEL_ID_BASE}/{name}",
                "state_dict": model.state_dict(),
                "optimizer": opt.state_dict(),
                "epoch": epoch,
                "stack_dim": STACK_DIM,
                "max_frames": MAX_FRAMES,
                "use_codec_aug": use_codec_aug,
                "tier": 1,
                "dev_eer": dev_metrics.get("eer"),
            },
            ckpt_path,
        )
    train_s = time.perf_counter() - t0

    torch.save(
        {
            "model_id": f"{MODEL_ID_BASE}/{name}",
            "state_dict": model.state_dict(),
            "optimizer": opt.state_dict(),
            "epoch": last_epoch,
            "stack_dim": STACK_DIM,
            "max_frames": MAX_FRAMES,
            "use_codec_aug": use_codec_aug,
            "tier": 1,
        },
        ckpt_path,
    )

    metrics: dict[str, Any] = {
        "model_id": f"{MODEL_ID_BASE}/{name}",
        "tier": 1,
        "use_codec_aug": use_codec_aug,
        "train_seconds": round(train_s, 2),
        "n_train": len(train_samples),
        "device": str(device),
        "epochs": epochs,
        "checkpoint": str(ckpt_path),
        "source": (
            "synthetic"
            if any(getattr(s, "source", "disk") == "synthetic" for s in train_samples)
            else "disk"
        ),
        "dev": evaluate_split(model, dev_samples, device, "dev"),
        "eval": {},
    }
    for key, samples in eval_map.items():
        metrics["eval"][key] = evaluate_split(model, samples, device, key)

    (out_dir / f"{name}_metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    return metrics


def build_eval_map(splits: dict[str, list[Sample]]) -> dict[str, list[Sample]]:
    eval_map = {
        "asvspoof2019_la_eval": splits.get("eval") or [],
        "asvspoof2021_df": splits.get("df2021") or [],
        "in_the_wild": splits.get("itw") or [],
    }
    if splits.get("itw"):
        eval_map["in_the_wild"] = splits["itw"]
    return eval_map


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Train Tier-1 LFCC-LCNN anti-spoof models")
    parser.add_argument("--datasets", type=Path, default=DEFAULT_DATASETS)
    parser.add_argument("--out", type=Path, default=ROOT / "models" / "antispoof")
    parser.add_argument("--limit", type=int, default=None, help="Cap files per split (smoke test)")
    parser.add_argument("--synthetic", action="store_true", help="Force synthetic smoke corpus")
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--aug-p", type=float, default=0.7)
    parser.add_argument("--codec-cache", type=Path, default=None)
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    parser.add_argument("--resume", action="store_true", help="Resume from existing *.pt if present")
    args = parser.parse_args(argv)

    present = corpora_present(args.datasets)
    if not args.synthetic and not primary_train_ready(args.datasets):
        print(
            "ERROR: ASVspoof 2019 LA train/dev not found under "
            f"{args.datasets}. Place corpora (see scripts/fetch_datasets.md) "
            "or pass --synthetic for a labelled smoke run.\n"
            f"  presence={present}",
            file=sys.stderr,
        )
        return 2

    splits = resolve_splits(args.datasets, limit=args.limit, synthetic=args.synthetic)
    train_s = splits.get("train") or []
    dev_s = splits.get("dev") or []
    if not train_s or not dev_s:
        print("ERROR: empty train/dev after resolve_splits", file=sys.stderr)
        return 2

    if not args.synthetic:
        assert_speaker_disjoint(train_s, dev_s, splits.get("eval") or [])

    eval_map = build_eval_map(splits)
    device = pick_device(args.device)
    cache_dir = args.codec_cache or (args.datasets / "_codec_cache")
    args.out.mkdir(parents=True, exist_ok=True)

    src_tag = "synthetic" if args.synthetic else "disk"
    print(
        f"train={len(train_s)} dev={len(dev_s)} source={src_tag} "
        f"device={device} resume={args.resume} presence={present}"
    )
    baseline = train_one(
        name="baseline_no_codec_aug",
        train_samples=train_s,
        dev_samples=dev_s,
        eval_map=eval_map,
        out_dir=args.out,
        epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
        use_codec_aug=False,
        aug_p=args.aug_p,
        cache_dir=cache_dir,
        device=device,
        resume=args.resume,
    )
    codec = train_one(
        name="codec_aug",
        train_samples=train_s,
        dev_samples=dev_s,
        eval_map=eval_map,
        out_dir=args.out,
        epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
        use_codec_aug=True,
        aug_p=args.aug_p,
        cache_dir=cache_dir,
        device=device,
        resume=args.resume,
    )

    # Before/after table for P12 (strongest technical artifact).
    table = {
        "note": (
            "In-the-Wild EER is expected to be much worse than in-domain "
            "(Context §3.1). Do not present train accuracy as general performance. "
            "Thresholds are never tuned on the evaluation set."
        ),
        "source": src_tag,
        "device": str(device),
        "baseline_no_codec_aug": baseline,
        "codec_aug": codec,
        "comparison": {},
    }
    for key in eval_map:
        b = baseline["eval"].get(key, {})
        c = codec["eval"].get(key, {})
        table["comparison"][key] = {
            "eer_baseline": b.get("eer"),
            "eer_codec_aug": c.get("eer"),
            "min_tdcf_baseline": b.get("min_tdcf"),
            "min_tdcf_codec_aug": c.get("min_tdcf"),
        }

    metrics_path = args.out / "metrics.json"
    metrics_path.write_text(json.dumps(table, indent=2), encoding="utf-8")
    print(f"Wrote {metrics_path}")
    print(json.dumps(table["comparison"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
