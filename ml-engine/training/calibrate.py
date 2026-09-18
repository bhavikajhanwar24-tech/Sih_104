#!/usr/bin/env python3
"""Platt scaling calibration of anti-spoof scores (Context §9 / §15).

Fit LogisticRegression on DEV scores SEPARATELY per channel profile.
Produces (A, B) coefficients, reliability diagram PNG, and ECE.

DO NOT fit on the evaluation set.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np
import torch

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from training.augment import codec_roundtrip  # noqa: E402
from training.dataset import DEFAULT_DATASETS, load_audio, resolve_splits  # noqa: E402
from training.features import STACK_DIM, TinyLCNN, lfcc_stack, pad_stack  # noqa: E402
from training.metrics import expected_calibration_error  # noqa: E402

SR = 16000
MAX_FRAMES = 200

PROFILE_CODECS = {
    "PSTN_NARROWBAND": "pcm_mulaw_8k",
    "VOIP_WIDEBAND": "libopus_24k",
    "WEBRTC_WIDEBAND": None,  # clean
}


def _load_model(ckpt: Path) -> TinyLCNN:
    blob = torch.load(ckpt, map_location="cpu", weights_only=False)
    model = TinyLCNN(int(blob.get("stack_dim", STACK_DIM)))
    model.load_state_dict(blob["state_dict"])
    model.eval()
    return model


@torch.inference_mode()
def _logits_for_profile(
    model: TinyLCNN,
    paths_labels: list[tuple[Path, int]],
    profile: str,
    cache_dir: Path,
) -> tuple[np.ndarray, np.ndarray]:
    codec = PROFILE_CODECS.get(profile)
    scores: list[float] = []
    labels: list[int] = []
    for path, label in paths_labels:
        audio = load_audio(path, SR)
        if codec is not None:
            try:
                audio = codec_roundtrip(audio, SR, codec, cache_dir=cache_dir, src_path=path)
            except Exception:
                pass
        stack = pad_stack(lfcc_stack(audio, SR, max_frames=MAX_FRAMES), MAX_FRAMES)
        x = torch.from_numpy(stack).unsqueeze(0).unsqueeze(0)
        logit = float(model(x).item())
        scores.append(logit)
        labels.append(label)
    return np.asarray(scores, dtype=np.float64), np.asarray(labels, dtype=np.int32)


def fit_platt(scores: np.ndarray, labels: np.ndarray) -> dict[str, float]:
    """Fit P(spoof|score) = sigmoid(A * score + B) via sklearn LogisticRegression."""
    from sklearn.linear_model import LogisticRegression

    if np.unique(labels).size < 2:
        return {"A": 1.0, "B": 0.0, "ece": 0.5, "n": int(labels.size)}
    x = scores.reshape(-1, 1)
    clf = LogisticRegression(solver="lbfgs", max_iter=1000)
    clf.fit(x, labels)
    A = float(clf.coef_.ravel()[0])
    B = float(clf.intercept_.ravel()[0])
    probs = 1.0 / (1.0 + np.exp(-(A * scores + B)))
    ece = expected_calibration_error(probs, labels)
    return {"A": A, "B": B, "ece": float(ece), "n": int(labels.size)}


def reliability_diagram(
    probs: np.ndarray,
    labels: np.ndarray,
    out_png: Path,
    *,
    title: str,
    n_bins: int = 10,
) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    bins = np.linspace(0.0, 1.0, n_bins + 1)
    centers = []
    accs = []
    confs = []
    for i in range(n_bins):
        mask = (probs >= bins[i]) & (probs < bins[i + 1] if i < n_bins - 1 else probs <= bins[i + 1])
        if not np.any(mask):
            continue
        centers.append(0.5 * (bins[i] + bins[i + 1]))
        accs.append(float(np.mean(labels[mask])))
        confs.append(float(np.mean(probs[mask])))
    fig, ax = plt.subplots(figsize=(5, 5))
    ax.plot([0, 1], [0, 1], "k--", label="perfect")
    if centers:
        ax.bar(centers, accs, width=0.08, alpha=0.6, label="empirical accuracy")
        ax.plot(centers, confs, "o-", color="C1", label="mean confidence")
    ax.set_xlabel("Predicted spoof probability")
    ax.set_ylabel("Accuracy / confidence")
    ax.set_title(title)
    ax.legend(loc="upper left")
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    out_png.parent.mkdir(parents=True, exist_ok=True)
    fig.tight_layout()
    fig.savefig(out_png, dpi=120)
    plt.close(fig)


def main() -> int:
    parser = argparse.ArgumentParser(description="Calibrate anti-spoof scores (Platt / DEV only)")
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--datasets", type=Path, default=DEFAULT_DATASETS)
    parser.add_argument("--out", type=Path, default=ROOT / "models" / "antispoof")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--synthetic", action="store_true")
    args = parser.parse_args()

    splits = resolve_splits(args.datasets, limit=args.limit, synthetic=args.synthetic)
    dev = splits.get("dev") or []
    if not dev:
        print("ERROR: empty DEV set", file=sys.stderr)
        return 2

    model = _load_model(args.checkpoint)
    cache_dir = args.datasets / "_codec_cache"
    paths_labels = [(s.path, int(s.label)) for s in dev]

    cal: dict[str, Any] = {
        "method": "platt",
        "fit_on": "dev_only",
        "checkpoint": str(args.checkpoint),
        "profiles": {},
    }
    args.out.mkdir(parents=True, exist_ok=True)

    for profile in PROFILE_CODECS:
        scores, labels = _logits_for_profile(model, paths_labels, profile, cache_dir)
        params = fit_platt(scores, labels)
        A, B = params["A"], params["B"]
        probs = 1.0 / (1.0 + np.exp(-(A * scores + B)))
        png = args.out / f"reliability_{profile}.png"
        reliability_diagram(
            probs,
            labels,
            png,
            title=f"Reliability — {profile} (ECE={params['ece']:.3f})",
        )
        params["reliability_png"] = str(png)
        cal["profiles"][profile] = params
        print(f"{profile}: A={A:.4f} B={B:.4f} ECE={params['ece']:.4f} -> {png}")

    out_json = args.out / "calibration.json"
    out_json.write_text(json.dumps(cal, indent=2), encoding="utf-8")
    # Convenience copy of the primary (wideband) diagram name expected by acceptance.
    primary = args.out / "reliability_diagram.png"
    src = Path(cal["profiles"]["WEBRTC_WIDEBAND"]["reliability_png"])
    if src.is_file():
        primary.write_bytes(src.read_bytes())
    print(f"Wrote {out_json} and {primary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
