"""CLI: dump spectral + phase features for a WAV (P4.1 debugging aid).

Usage:
  python -m scripts.inspect_features path/to.wav --profile narrowband
  python -m scripts.inspect_features --compare bonafide.wav spoof.wav --profile wideband
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any

import numpy as np
import soundfile as sf

from app.modules.phase import extract as extract_phase
from app.modules.spectral import extract as extract_spectral
from app.modules.spectral import reset_cqt_cache
from app.normaliser import normalise
from app.types import ChannelProfile

_PROFILE_ALIASES = {
    "narrowband": ChannelProfile.PSTN_NARROWBAND,
    "pstn": ChannelProfile.PSTN_NARROWBAND,
    "pstn_narrowband": ChannelProfile.PSTN_NARROWBAND,
    "wideband": ChannelProfile.WEBRTC_WIDEBAND,
    "webrtc": ChannelProfile.WEBRTC_WIDEBAND,
    "webrtc_wideband": ChannelProfile.WEBRTC_WIDEBAND,
    "voip": ChannelProfile.VOIP_WIDEBAND,
    "voip_wideband": ChannelProfile.VOIP_WIDEBAND,
}


def _parse_profile(name: str) -> ChannelProfile:
    key = name.strip().lower().replace("-", "_")
    if key in _PROFILE_ALIASES:
        return _PROFILE_ALIASES[key]
    return ChannelProfile(name)


def _load_mono_float(path: Path) -> tuple[np.ndarray, int]:
    audio, sr = sf.read(str(path), always_2d=False)
    if getattr(audio, "ndim", 1) > 1:
        audio = np.mean(audio, axis=1)
    pcm = np.asarray(audio, dtype=np.float32)
    # Route through the project normaliser when already float @ arbitrary rate.
    raw = pcm.astype("<f4").tobytes()
    out, _ = normalise(raw, "pcm_f32le", int(sr), 1)
    return out, 16000


def _extract_all(path: Path, profile: ChannelProfile) -> dict[str, Any]:
    audio, sr = _load_mono_float(path)
    # Use up to 2.0 s (pipeline window).
    n = min(audio.size, sr * 2)
    window = audio[:n]
    reset_cqt_cache()
    spectral = extract_spectral(window, sr, profile, force_cqt=True)
    phase = extract_phase(window, sr)
    merged = {f"spectral.{k}": v for k, v in spectral.items()}
    merged.update({f"phase.{k}": v for k, v in phase.items()})
    return merged


def _fmt(value: Any) -> str:
    if value is None:
        return "None"
    if isinstance(value, bool):
        return str(value)
    if isinstance(value, float):
        return f"{value:.6g}"
    return str(value)


def _print_table(feats: dict[str, Any], title: str) -> None:
    print(f"\n=== {title} ===")
    width = max(len(k) for k in feats) if feats else 10
    for key in sorted(feats):
        print(f"{key:<{width}}  {_fmt(feats[key])}")


def _print_compare(a: dict[str, Any], b: dict[str, Any], label_a: str, label_b: str) -> None:
    keys = sorted(set(a) | set(b))
    w = max(len(k) for k in keys) if keys else 10
    print(f"\n{'feature':<{w}}  {label_a:>14}  {label_b:>14}  {'delta':>14}")
    print("-" * (w + 48))
    for key in keys:
        va, vb = a.get(key), b.get(key)
        if isinstance(va, (int, float)) and isinstance(vb, (int, float)) and not isinstance(va, bool):
            delta = float(vb) - float(va)
            print(f"{key:<{w}}  {_fmt(va):>14}  {_fmt(vb):>14}  {delta:>14.6g}")
        else:
            print(f"{key:<{w}}  {_fmt(va):>14}  {_fmt(vb):>14}  {'':>14}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Inspect SentinelVoice spectral/phase features")
    parser.add_argument("wav", nargs="?", help="WAV file to inspect")
    parser.add_argument(
        "--profile",
        default="wideband",
        help="narrowband|wideband|voip (default: wideband)",
    )
    parser.add_argument(
        "--compare",
        nargs=2,
        metavar=("BONAFIDE", "SPOOF"),
        help="compare two WAVs side by side with deltas",
    )
    args = parser.parse_args(argv)
    profile = _parse_profile(args.profile)

    if args.compare:
        path_a = Path(args.compare[0])
        path_b = Path(args.compare[1])
        feats_a = _extract_all(path_a, profile)
        feats_b = _extract_all(path_b, profile)
        _print_compare(feats_a, feats_b, path_a.name, path_b.name)
        # Slide-friendly summary line.
        for key in ("spectral.high_band_ratio", "phase.phase_entropy_mean"):
            if key in feats_a and key in feats_b:
                print(
                    f"\nSLIDE  {key}:  {path_a.name}={_fmt(feats_a[key])}  "
                    f"{path_b.name}={_fmt(feats_b[key])}  "
                    f"delta={float(feats_b[key]) - float(feats_a[key]):.6g}"
                )
        return 0

    if not args.wav:
        parser.error("wav path required (or use --compare a.wav b.wav)")
    path = Path(args.wav)
    feats = _extract_all(path, profile)
    _print_table(feats, f"{path.name}  profile={profile.value}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
