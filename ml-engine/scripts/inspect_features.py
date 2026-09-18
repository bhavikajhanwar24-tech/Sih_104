"""CLI: dump spectral / phase / prosody / breath features for a WAV.

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

from app.modules.breath import detect_breaths
from app.modules.phase import extract as extract_phase
from app.modules.prosody import extract as extract_prosody
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
    raw = pcm.astype("<f4").tobytes()
    out, _ = normalise(raw, "pcm_f32le", int(sr), 1)
    return out, 16000


def _extract_all(path: Path, profile: ChannelProfile) -> dict[str, Any]:
    audio, sr = _load_mono_float(path)
    # Prosody needs >=0.5s voiced; use up to 8s.
    n = min(audio.size, sr * 8)
    window = audio[:n]
    reset_cqt_cache()
    spectral = extract_spectral(window[: min(window.size, sr * 2)], sr, profile, force_cqt=True)
    phase = extract_phase(window[: min(window.size, sr * 2)], sr)
    prosody = extract_prosody(window, sr)
    # Treat whole file duration as cumulative speech for absence scoring demos.
    breath = detect_breaths(window, sr, cumulative_speech_s=max(20.0, n / sr))
    merged = {f"spectral.{k}": v for k, v in spectral.items()}
    merged.update({f"phase.{k}": v for k, v in phase.items()})
    merged.update({f"prosody.{k}": v for k, v in prosody.items() if k != "event_list"})
    merged.update({f"breath.{k}": v for k, v in breath.items() if k != "event_list"})
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


_SLIDE_KEYS = (
    "spectral.high_band_ratio",
    "phase.phase_entropy_mean",
    "prosody.jitter_local",
    "prosody.unnaturalness_score",
    "breath.breath_events_per_min",
    "breath.breath_absence_score",
)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Inspect SentinelVoice DSP features")
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
        for key in _SLIDE_KEYS:
            if key in feats_a and key in feats_b:
                va, vb = feats_a[key], feats_b[key]
                if isinstance(va, (int, float)) and isinstance(vb, (int, float)):
                    print(
                        f"\nSLIDE  {key}:  {path_a.name}={_fmt(va)}  "
                        f"{path_b.name}={_fmt(vb)}  "
                        f"delta={float(vb) - float(va):.6g}"
                    )
                else:
                    print(f"\nSLIDE  {key}:  {path_a.name}={_fmt(va)}  {path_b.name}={_fmt(vb)}")
        return 0

    if not args.wav:
        parser.error("wav path required (or use --compare a.wav b.wav)")
    path = Path(args.wav)
    feats = _extract_all(path, profile)
    _print_table(feats, f"{path.name}  profile={profile.value}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
