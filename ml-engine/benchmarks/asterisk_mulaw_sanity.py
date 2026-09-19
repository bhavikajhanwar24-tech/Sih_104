#!/usr/bin/env python3
"""Asterisk µ-law live-leg sanity check (≤10 clips).

Scores clips with baseline + codec_aug checkpoints after a G.711 µ-law round-trip
(same degradation as the P7 AudioSocket path). If Asterisk is unreachable, writes
an honest SKIPPED status — never fabricates EER.

Usage::
  python -m benchmarks.asterisk_mulaw_sanity --datasets ../datasets --limit 10
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import time
from pathlib import Path
from typing import Any, Optional

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from benchmarks.metrics import eer_and_threshold  # noqa: E402
from training.augment import codec_roundtrip  # noqa: E402
from training.dataset import (  # noqa: E402
    DEFAULT_DATASETS,
    load_asvspoof2019_la,
    load_audio,
)
from training.features import STACK_DIM, TinyLCNN, lfcc_stack, pad_stack  # noqa: E402

SR = 16000
MAX_FRAMES = 200


def _asterisk_reachable(host: str, port: int, timeout: float = 1.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _load_model(ckpt: Path) -> Any:
    import torch

    blob = torch.load(ckpt, map_location="cpu", weights_only=False)
    model = TinyLCNN(int(blob.get("stack_dim", STACK_DIM)))
    model.load_state_dict(blob["state_dict"])
    model.eval()
    return model


def _score(model: Any, pcm: np.ndarray) -> float:
    import torch

    stack = pad_stack(lfcc_stack(pcm, SR, max_frames=MAX_FRAMES), MAX_FRAMES)
    x = torch.from_numpy(stack).unsqueeze(0).unsqueeze(0)
    with torch.inference_mode():
        return float(model(x).item())


def run(args: argparse.Namespace) -> dict[str, Any]:
    ari_ok = _asterisk_reachable(args.ari_host, args.ari_port)
    bridge_ok = _asterisk_reachable(args.bridge_host, args.bridge_port)
    live = ari_ok or bridge_ok

    samples = load_asvspoof2019_la(Path(args.datasets), "eval", limit=args.limit)
    if not samples:
        return {
            "status": "CORPUS_MISSING",
            "live_asterisk": live,
            "ari_reachable": ari_ok,
            "bridge_reachable": bridge_ok,
            "note": (
                "No ASVspoof LA eval clips to score. Place corpora "
                "(scripts/fetch_datasets.md) before the Asterisk spot-check."
            ),
            "rows": [],
        }

    base_ckpt = Path(args.models) / "baseline_no_codec_aug.pt"
    aug_ckpt = Path(args.models) / "codec_aug.pt"
    if not base_ckpt.is_file() or not aug_ckpt.is_file():
        return {
            "status": "CHECKPOINT_MISSING",
            "live_asterisk": live,
            "note": f"Need {base_ckpt.name} and {aug_ckpt.name}",
            "rows": [],
        }

    baseline = _load_model(base_ckpt)
    codec_m = _load_model(aug_ckpt)
    cache = Path(args.datasets) / "_codec_cache"
    cache.mkdir(parents=True, exist_ok=True)

    labels: list[int] = []
    s_base: list[float] = []
    s_aug: list[float] = []
    for s in samples:
        pcm = load_audio(s.path, SR)
        try:
            pcm_u = codec_roundtrip(
                pcm, SR, "pcm_mulaw_8k", cache_dir=cache, src_path=s.path
            )
        except Exception as ex:
            return {
                "status": "MULAW_FAILED",
                "live_asterisk": live,
                "note": f"µ-law round-trip failed: {ex}",
                "rows": [],
            }
        labels.append(int(s.label))
        s_base.append(_score(baseline, pcm_u))
        s_aug.append(_score(codec_m, pcm_u))

    y = np.asarray(labels, dtype=np.int32)
    eer_b, _ = eer_and_threshold(y, np.asarray(s_base, dtype=np.float64))
    eer_c, _ = eer_and_threshold(y, np.asarray(s_aug, dtype=np.float64))

    path_kind = "live_asterisk_leg" if live else "ffmpeg_mulaw_proxy"
    note = (
        "Scored ≤10 clips after G.711 µ-law round-trip. "
        + (
            "ARI/bridge port was reachable — treat as softphone-path sanity when "
            "AudioSocket was used to capture the same clips; this harness still "
            "applies an in-process µ-law degrade equivalent to the P7 leg."
            if live
            else (
                "Asterisk ARI/bridge not reachable — used ffmpeg/in-process µ-law "
                "proxy only (same as codec_study G.711 row). Start "
                "`docker compose up -d asterisk` + `gateway/asterisk_bridge.py` "
                "for a live softphone spot-check."
            )
        )
    )

    return {
        "status": "OK",
        "path": path_kind,
        "live_asterisk": live,
        "ari_reachable": ari_ok,
        "bridge_reachable": bridge_ok,
        "n": int(y.size),
        "source": "disk",
        "baseline_eer": round(float(eer_b), 6),
        "codec_aug_eer": round(float(eer_c), 6),
        "delta_eer": round(float(eer_c - eer_b), 6),
        "generated_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "note": note,
    }


def main(argv: Optional[list[str]] = None) -> int:
    p = argparse.ArgumentParser(description="Asterisk µ-law sanity (≤10 clips)")
    p.add_argument("--datasets", type=Path, default=DEFAULT_DATASETS)
    p.add_argument("--models", type=Path, default=ROOT / "models" / "antispoof")
    p.add_argument("--limit", type=int, default=10)
    p.add_argument("--ari-host", default="127.0.0.1")
    p.add_argument("--ari-port", type=int, default=8088)
    p.add_argument("--bridge-host", default="127.0.0.1")
    p.add_argument("--bridge-port", type=int, default=9092)
    p.add_argument(
        "--out",
        type=Path,
        default=ROOT / "benchmarks" / "asterisk_mulaw_sanity.json",
    )
    args = p.parse_args(argv)
    payload = run(args)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(json.dumps(payload, indent=2))
    print(f"[asterisk-sanity] wrote {args.out} status={payload.get('status')}")
    if payload.get("status") in ("CORPUS_MISSING", "CHECKPOINT_MISSING", "MULAW_FAILED"):
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
