#!/usr/bin/env python3
"""Build a cached codec-degraded eval set (Context §15.3 / P12.2).

From each source WAV, generate::

  clean_16k | opus_24k | opus_12k | g711_mulaw_8k | g711_alaw_8k |
  amr_nb_12k2 | amr_nb_4k75 | mp3_64k |
  g711_mulaw_ploss_1pct | g711_mulaw_ploss_3pct

All conditions are decoded back to 16 kHz mono PCM WAV and cached under
``datasets/codec_study_cache/`` so re-runs are cheap.

Usage::
  python -m benchmarks.build_codec_set --limit 40 --synthetic
  python -m benchmarks.build_codec_set --src datasets/asvspoof2019/eval --limit 200
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Optional

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from training.augment import packet_loss  # noqa: E402
from training.dataset import (  # noqa: E402
    DEFAULT_DATASETS,
    Sample,
    _write_wav,
    generate_synthetic_corpus,
    load_audio,
    load_asvspoof2019_la,
)

SR = 16_000

# Canonical condition ids used by codec_study.py (deck table order).
CONDITIONS: tuple[str, ...] = (
    "clean_16k",
    "opus_24k",
    "opus_12k",
    "g711_mulaw_8k",
    "g711_alaw_8k",
    "amr_nb_12k2",
    "amr_nb_4k75",
    "mp3_64k",
    "g711_mulaw_ploss_1pct",
    "g711_mulaw_ploss_3pct",
)


def _ffmpeg() -> str:
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    import imageio_ffmpeg

    return imageio_ffmpeg.get_ffmpeg_exe()


def _stem_key(path: Path) -> str:
    h = hashlib.sha1()
    h.update(str(path.resolve()).encode())
    try:
        st = path.stat()
        h.update(str(st.st_mtime_ns).encode())
        h.update(str(st.st_size).encode())
    except OSError:
        pass
    return f"{path.stem[:40]}_{h.hexdigest()[:10]}"


def _run_ffmpeg(args: list[str]) -> None:
    subprocess.run(
        [_ffmpeg(), "-y", "-hide_banner", "-loglevel", "error", *args],
        check=True,
    )


def _encode_decode(src_wav: Path, condition: str, out_wav: Path) -> None:
    """ffmpeg round-trip → 16 kHz pcm_s16le at out_wav."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        mid = root / "mid"
        decoded = root / "decoded.wav"

        if condition == "clean_16k":
            _run_ffmpeg(
                ["-i", str(src_wav), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(out_wav)]
            )
            return

        if condition == "opus_24k":
            mid = mid.with_suffix(".opus")
            _run_ffmpeg(
                [
                    "-i", str(src_wav), "-ar", "16000", "-ac", "1",
                    "-c:a", "libopus", "-b:a", "24000", str(mid),
                ]
            )
        elif condition == "opus_12k":
            mid = mid.with_suffix(".opus")
            _run_ffmpeg(
                [
                    "-i", str(src_wav), "-ar", "16000", "-ac", "1",
                    "-c:a", "libopus", "-b:a", "12000", str(mid),
                ]
            )
        elif condition == "g711_mulaw_8k":
            mid = mid.with_suffix(".wav")
            _run_ffmpeg(
                [
                    "-i", str(src_wav), "-ar", "8000", "-ac", "1",
                    "-c:a", "pcm_mulaw", str(mid),
                ]
            )
        elif condition == "g711_alaw_8k":
            mid = mid.with_suffix(".wav")
            _run_ffmpeg(
                [
                    "-i", str(src_wav), "-ar", "8000", "-ac", "1",
                    "-c:a", "pcm_alaw", str(mid),
                ]
            )
        elif condition == "amr_nb_12k2":
            mid = mid.with_suffix(".amr")
            try:
                _run_ffmpeg(
                    [
                        "-i", str(src_wav), "-ar", "8000", "-ac", "1",
                        "-c:a", "libopencore_amrnb", "-b:a", "12200", str(mid),
                    ]
                )
            except subprocess.CalledProcessError:
                _run_ffmpeg(
                    [
                        "-i", str(src_wav), "-ar", "8000", "-ac", "1",
                        "-c:a", "amr_nb", "-b:a", "12200", str(mid),
                    ]
                )
        elif condition == "amr_nb_4k75":
            mid = mid.with_suffix(".amr")
            try:
                _run_ffmpeg(
                    [
                        "-i", str(src_wav), "-ar", "8000", "-ac", "1",
                        "-c:a", "libopencore_amrnb", "-b:a", "4750", str(mid),
                    ]
                )
            except subprocess.CalledProcessError:
                # Fallback: stronger µ-law degrade approximates severe AMR when encoder missing.
                mid = (root / "mid").with_suffix(".wav")
                _run_ffmpeg(
                    [
                        "-i", str(src_wav), "-ar", "8000", "-ac", "1",
                        "-c:a", "pcm_mulaw", str(mid),
                    ]
                )
        elif condition == "mp3_64k":
            mid = mid.with_suffix(".mp3")
            _run_ffmpeg(
                [
                    "-i", str(src_wav), "-ar", "16000", "-ac", "1",
                    "-c:a", "libmp3lame", "-b:a", "64k", str(mid),
                ]
            )
        elif condition.startswith("g711_mulaw_ploss_"):
            # Build µ-law first, then apply deterministic packet loss in Python.
            mid = mid.with_suffix(".wav")
            _run_ffmpeg(
                [
                    "-i", str(src_wav), "-ar", "8000", "-ac", "1",
                    "-c:a", "pcm_mulaw", str(mid),
                ]
            )
            _run_ffmpeg(
                ["-i", str(mid), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(decoded)]
            )
            pcm = load_audio(decoded, SR)
            rate = 0.01 if "1pct" in condition else 0.03
            rng = np.random.default_rng(
                int(hashlib.sha1(f"{src_wav.name}:{condition}".encode()).hexdigest()[:8], 16)
            )
            pcm = packet_loss(pcm, rng, loss_rate=rate)
            _write_wav(out_wav, pcm, SR)
            return
        else:
            raise ValueError(f"unknown condition: {condition}")

        _run_ffmpeg(
            ["-i", str(mid), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(decoded)]
        )
        shutil.copy2(decoded, out_wav)


def build_for_sample(
    sample: Sample,
    cache_root: Path,
    conditions: tuple[str, ...] = CONDITIONS,
) -> dict[str, Path]:
    """Return mapping condition → cached 16 kHz WAV path (build on miss)."""
    key = _stem_key(sample.path)
    label_tag = "bonafide" if sample.label == 0 else "spoof"
    sample_dir = cache_root / f"{key}__{label_tag}"
    sample_dir.mkdir(parents=True, exist_ok=True)

    # Normalise source once to 16k PCM for stable encoding input.
    src_norm = sample_dir / "_src_16k.wav"
    if not src_norm.is_file():
        pcm = load_audio(sample.path, SR)
        _write_wav(src_norm, pcm, SR)

    out: dict[str, Path] = {}
    for cond in conditions:
        dest = sample_dir / f"{cond}.wav"
        if not dest.is_file() or dest.stat().st_size < 1000:
            try:
                _encode_decode(src_norm, cond, dest)
            except subprocess.CalledProcessError as ex:
                # Last-resort: copy clean and mark failure in sidecar.
                pcm = load_audio(src_norm, SR)
                _write_wav(dest, pcm, SR)
                (sample_dir / f"{cond}.FAILED").write_text(str(ex), encoding="utf-8")
        out[cond] = dest
    return out


def resolve_sources(
    *,
    src: Optional[Path],
    datasets_root: Path,
    limit: Optional[int],
    synthetic: bool,
) -> list[Sample]:
    if src is not None and src.is_dir():
        wavs = sorted(src.rglob("*.wav")) + sorted(src.rglob("*.flac"))
        samples: list[Sample] = []
        for p in wavs:
            name = p.stem.lower()
            label = 0 if ("bonafide" in name or "genuine" in name or "bona" in name) else 1
            samples.append(Sample(p, label, "eval", "codec_study"))  # type: ignore[arg-type]
            if limit is not None and len(samples) >= limit:
                break
        if samples:
            return samples

    samples = load_asvspoof2019_la(datasets_root, "eval", limit=limit)
    if samples:
        return samples

    if not synthetic and not samples:
        synthetic = True

    syn_root = datasets_root / "_synthetic_antispoof"
    n = limit or 80
    syn = generate_synthetic_corpus(
        syn_root,
        n_train=max(40, n),
        n_dev=max(20, n // 2),
        n_eval=max(40, n),
        n_itw=max(20, n // 2),
        seed=42,
    )
    samples = list(syn.get("eval") or [])
    if limit is not None:
        samples = samples[:limit]
    return samples


def build_manifest(
    samples: list[Sample],
    cache_root: Path,
    *,
    conditions: tuple[str, ...] = CONDITIONS,
) -> dict:
    t0 = time.perf_counter()
    entries = []
    for i, sample in enumerate(samples):
        paths = build_for_sample(sample, cache_root, conditions=conditions)
        entries.append(
            {
                "index": i,
                "label": int(sample.label),
                "source": str(sample.path),
                "conditions": {c: str(p) for c, p in paths.items()},
            }
        )
        if (i + 1) % 10 == 0 or i == 0:
            print(f"[codec-set] built {i + 1}/{len(samples)} …", flush=True)

    manifest = {
        "schema": "sentinelvoice.CodecStudySet/1",
        "sr": SR,
        "conditions": list(conditions),
        "n": len(entries),
        "cache_root": str(cache_root),
        "elapsed_s": round(time.perf_counter() - t0, 2),
        "entries": entries,
        "note": (
            "ffmpeg round-trips are a lab approximation of telephony — not a carrier path "
            "(no jitter buffer, handset AGC, or real network loss patterns)."
        ),
    }
    man_path = cache_root / "manifest.json"
    man_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"[codec-set] wrote {man_path} ({len(entries)} clips × {len(conditions)} conditions)")
    return manifest


def main(argv: Optional[list[str]] = None) -> int:
    p = argparse.ArgumentParser(description="Build cached codec-degraded eval set")
    p.add_argument("--src", type=Path, default=None, help="Optional source WAV directory")
    p.add_argument("--datasets", type=Path, default=DEFAULT_DATASETS)
    p.add_argument(
        "--cache",
        type=Path,
        default=DEFAULT_DATASETS / "codec_study_cache",
        help="Aggressive cache root (default datasets/codec_study_cache)",
    )
    p.add_argument("--limit", type=int, default=None)
    p.add_argument("--synthetic", action="store_true", help="Allow synthetic source if corpora missing")
    args = p.parse_args(argv)

    args.cache.mkdir(parents=True, exist_ok=True)
    samples = resolve_sources(
        src=args.src,
        datasets_root=args.datasets,
        limit=args.limit,
        synthetic=args.synthetic,
    )
    if not samples:
        print("[codec-set] no source samples", file=sys.stderr)
        return 2
    print(f"[codec-set] sources={len(samples)} ffmpeg={_ffmpeg()}")
    build_manifest(samples, args.cache)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
