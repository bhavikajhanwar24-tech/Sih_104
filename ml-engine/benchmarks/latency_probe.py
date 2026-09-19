#!/usr/bin/env python3
"""Latency probe for Tier-1 anti-spoof (p50/p95/p99).

Uses the real LFCC-LCNN checkpoint when present. Does not invent numbers.

Usage::
  python -m benchmarks.latency_probe --n 40
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
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from benchmarks.metrics import latency_summary  # noqa: E402
from training.dataset import DEFAULT_DATASETS, load_asvspoof2019_la, load_audio  # noqa: E402
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


def run(args: argparse.Namespace) -> dict[str, Any]:
    import torch

    ckpt = Path(args.checkpoint)
    if not ckpt.is_file():
        return {
            "status": "CHECKPOINT_MISSING",
            "note": f"No checkpoint at {ckpt} — not inventing latency numbers.",
        }

    model = _load_model(ckpt)
    cal_path = ckpt.parent / "calibration.json"
    calibration: dict[str, dict[str, float]] = {}
    if cal_path.is_file():
        cal = json.loads(cal_path.read_text(encoding="utf-8"))
        for prof, params in (cal.get("profiles") or {}).items():
            calibration[prof] = {"A": float(params["A"]), "B": float(params["B"])}

    def platt(logit: float) -> float:
        params = calibration.get("WEBRTC_WIDEBAND") or next(iter(calibration.values()), None)
        x = float(logit)
        if params:
            x = float(params["A"]) * x + float(params["B"])
        x = float(np.clip(x, -40, 40))
        return float(1.0 / (1.0 + np.exp(-x)))

    samples = load_asvspoof2019_la(Path(args.datasets), "eval", limit=args.n)
    source = "disk" if samples else "synthetic_noise"
    pcm_list: list[np.ndarray] = []
    if samples:
        for s in samples:
            pcm_list.append(load_audio(s.path, SR))
    else:
        rng = np.random.default_rng(0)
        for _ in range(args.n):
            pcm_list.append((0.05 * rng.standard_normal(SR * 2)).astype(np.float32))

    fast: list[float] = []
    model.eval()
    with torch.inference_mode():
        # Warmup
        stack = pad_stack(lfcc_stack(pcm_list[0], SR, max_frames=MAX_FRAMES), MAX_FRAMES)
        _ = model(torch.from_numpy(stack).unsqueeze(0).unsqueeze(0))
        for pcm in pcm_list:
            t0 = time.perf_counter()
            stack = pad_stack(lfcc_stack(pcm, SR, max_frames=MAX_FRAMES), MAX_FRAMES)
            x = torch.from_numpy(stack).unsqueeze(0).unsqueeze(0)
            logit = float(model(x).item())
            _ = platt(logit)
            fast.append((time.perf_counter() - t0) * 1000.0)

    return {
        "status": "OK",
        "source": source,
        "checkpoint": str(ckpt),
        "n": len(fast),
        "fast_path_ms": latency_summary(fast),
        "note": (
            "fast_path = LFCC + TinyLCNN + Platt on a ~2 s window. "
            "When ASVspoof eval is absent, PCM is synthetic noise (labelled)."
            if source != "disk"
            else "Measured on ASVspoof 2019 LA eval clips."
        ),
    }


def main(argv: Optional[list[str]] = None) -> int:
    p = argparse.ArgumentParser(description="Anti-spoof latency p50/p95/p99")
    p.add_argument("--checkpoint", type=Path, default=ROOT / "models" / "antispoof" / "codec_aug.pt")
    p.add_argument("--datasets", type=Path, default=DEFAULT_DATASETS)
    p.add_argument("--n", type=int, default=40)
    p.add_argument("--out", type=Path, default=ROOT / "benchmarks" / "latency_results.json")
    args = p.parse_args(argv)
    payload = run(args)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(json.dumps(payload, indent=2))
    if payload.get("status") != "OK":
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
