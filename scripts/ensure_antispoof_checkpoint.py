#!/usr/bin/env python3
"""Ensure Tier-1 antispoof checkpoint exists for live voice scores.

If models/antispoof/codec_aug.pt is missing, trains a tiny synthetic checkpoint
(hackathon lab — not a production EER claim).

Usage (repo root, ml-engine venv)::
  python scripts/ensure_antispoof_checkpoint.py
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ML = ROOT / "ml-engine"
OUT = ML / "models" / "antispoof"
CKPT = OUT / "codec_aug.pt"
CAL = OUT / "calibration.json"


def main() -> int:
    if CKPT.is_file() and CAL.is_file():
        print(f"antispoof checkpoint ok: {CKPT}")
        return 0

    OUT.mkdir(parents=True, exist_ok=True)
    py = sys.executable
    cmd = [
        py,
        "-m",
        "training.train_antispoof",
        "--synthetic",
        "--limit",
        "120",
        "--epochs",
        "2",
        "--out",
        str(OUT),
    ]
    print("Training synthetic antispoof checkpoint:", " ".join(cmd))
    proc = subprocess.run(cmd, cwd=str(ML))
    if proc.returncode != 0:
        return proc.returncode

    # train_antispoof writes codec_aug.pt under out/; ensure calibration.json exists
    if not CAL.is_file():
        # Identity Platt params — sigmoid(logit); train can replace later via calibrate.py
        profiles = {
            p: {"A": 1.0, "B": 0.0}
            for p in ("WEBRTC_WIDEBAND", "VOIP_WIDEBAND", "PSTN_NARROWBAND")
        }
        CAL.write_text(json.dumps({"profiles": profiles}, indent=2), encoding="utf-8")
        print(f"Wrote fallback calibration {CAL}")

    if not CKPT.is_file():
        print(f"ERROR: expected checkpoint missing after train: {CKPT}", file=sys.stderr)
        return 2
    print(f"antispoof checkpoint ready: {CKPT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
