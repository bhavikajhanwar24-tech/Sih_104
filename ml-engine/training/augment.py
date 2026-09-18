"""Codec / channel augmentation for anti-spoofing training (Context §3 / §10.1).

ffmpeg round-trips are cached on disk once — never re-run every epoch.
"""

from __future__ import annotations

import hashlib
import shutil
import subprocess
import tempfile
import wave
from pathlib import Path
from typing import Optional

import numpy as np
from numpy.typing import NDArray

from training.dataset import SR, _write_wav, load_audio

CODEC_VARIANTS = (
    "pcm_mulaw_8k",
    "pcm_alaw_8k",
    "amr_nb_12k2",
    "amr_nb_4k75",
    "libopus_24k",
    "libopus_12k",
)


def _ffmpeg() -> str:
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    import imageio_ffmpeg

    return imageio_ffmpeg.get_ffmpeg_exe()


def _cache_key(path: Path, variant: str) -> str:
    h = hashlib.sha1()
    h.update(str(path.resolve()).encode())
    h.update(variant.encode())
    try:
        st = path.stat()
        h.update(str(st.st_mtime_ns).encode())
        h.update(str(st.st_size).encode())
    except OSError:
        pass
    return h.hexdigest()[:16]


def codec_roundtrip(
    audio: NDArray[np.floating],
    sr: int,
    variant: str,
    *,
    cache_dir: Optional[Path] = None,
    src_path: Optional[Path] = None,
) -> NDArray[np.floating]:
    """Apply one telephony/VoIP codec round-trip, returning 16 kHz float32."""
    ffmpeg = _ffmpeg()
    cache_dir = cache_dir or Path("datasets/_codec_cache")
    cache_dir.mkdir(parents=True, exist_ok=True)

    if src_path is not None:
        key = _cache_key(src_path, variant)
        cached = cache_dir / f"{key}__{variant}.wav"
        if cached.is_file():
            return load_audio(cached, SR)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        src = root / "src.wav"
        mid = root / "mid"
        out = root / "out.wav"
        _write_wav(src, audio, sr)

        if variant == "pcm_mulaw_8k":
            mid = mid.with_suffix(".wav")
            subprocess.run(
                [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src),
                 "-ar", "8000", "-ac", "1", "-c:a", "pcm_mulaw", str(mid)],
                check=True,
            )
        elif variant == "pcm_alaw_8k":
            mid = mid.with_suffix(".wav")
            subprocess.run(
                [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src),
                 "-ar", "8000", "-ac", "1", "-c:a", "pcm_alaw", str(mid)],
                check=True,
            )
        elif variant == "amr_nb_12k2":
            mid = mid.with_suffix(".amr")
            try:
                subprocess.run(
                    [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src),
                     "-ar", "8000", "-ac", "1", "-c:a", "libopencore_amrnb", "-b:a", "12200", str(mid)],
                    check=True,
                )
            except subprocess.CalledProcessError:
                subprocess.run(
                    [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src),
                     "-ar", "8000", "-ac", "1", "-c:a", "amr_nb", "-b:a", "12200", str(mid)],
                    check=True,
                )
        elif variant == "amr_nb_4k75":
            mid = mid.with_suffix(".amr")
            try:
                subprocess.run(
                    [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src),
                     "-ar", "8000", "-ac", "1", "-c:a", "libopencore_amrnb", "-b:a", "4750", str(mid)],
                    check=True,
                )
            except subprocess.CalledProcessError:
                subprocess.run(
                    [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src),
                     "-ar", "8000", "-ac", "1", "-c:a", "amr_nb", "-b:a", "4750", str(mid)],
                    check=True,
                )
        elif variant == "libopus_24k":
            mid = mid.with_suffix(".opus")
            subprocess.run(
                [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src),
                 "-ar", "16000", "-ac", "1", "-c:a", "libopus", "-b:a", "24000", str(mid)],
                check=True,
            )
        elif variant == "libopus_12k":
            mid = mid.with_suffix(".opus")
            subprocess.run(
                [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src),
                 "-ar", "16000", "-ac", "1", "-c:a", "libopus", "-b:a", "12000", str(mid)],
                check=True,
            )
        else:
            raise ValueError(f"unknown codec variant: {variant}")

        subprocess.run(
            [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(mid),
             "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(out)],
            check=True,
        )
        degraded = load_audio(out, SR)

        if src_path is not None:
            key = _cache_key(src_path, variant)
            cached = cache_dir / f"{key}__{variant}.wav"
            _write_wav(cached, degraded, SR)
        return degraded


def _noise_bank(rng: np.random.Generator, n: int, kind: str) -> NDArray[np.floating]:
    white = rng.standard_normal(n)
    if kind == "office":
        # Mild low-pass.
        out = np.zeros(n)
        a = 0.9
        for i in range(1, n):
            out[i] = a * out[i - 1] + (1 - a) * white[i]
        return out.astype(np.float64)
    if kind == "street":
        t = np.arange(n) / SR
        return (white + 0.3 * np.sin(2 * np.pi * 40 * t)).astype(np.float64)
    # babble: sum of modulated tones
    t = np.arange(n) / SR
    babble = np.zeros(n)
    for _ in range(5):
        f0 = rng.uniform(80, 250)
        babble += np.sin(2 * np.pi * f0 * t) * (0.5 + 0.5 * np.sin(2 * np.pi * rng.uniform(2, 6) * t))
    return (0.4 * babble + 0.6 * white).astype(np.float64)


def add_noise(
    audio: NDArray[np.floating],
    rng: np.random.Generator,
    snr_db: Optional[float] = None,
) -> NDArray[np.floating]:
    snr = float(rng.uniform(5.0, 25.0) if snr_db is None else snr_db)
    kind = str(rng.choice(["babble", "office", "street"]))
    noise = _noise_bank(rng, audio.size, kind)
    sig_p = float(np.mean(audio.astype(np.float64) ** 2) + 1e-12)
    noi_p = float(np.mean(noise**2) + 1e-12)
    scale = np.sqrt(sig_p / (noi_p * 10 ** (snr / 10.0)))
    out = audio.astype(np.float64) + scale * noise
    peak = np.max(np.abs(out)) + 1e-12
    return (out / peak * 0.9).astype(np.float32)


def packet_loss(
    audio: NDArray[np.floating],
    rng: np.random.Generator,
    *,
    loss_rate: Optional[float] = None,
) -> NDArray[np.floating]:
    """Zero or repeat random 20–60 ms segments at 0–5% loss."""
    rate = float(rng.uniform(0.0, 0.05) if loss_rate is None else loss_rate)
    if rate <= 0:
        return audio.astype(np.float32)
    out = audio.astype(np.float32).copy()
    n = out.size
    lost = 0
    target = int(rate * n)
    while lost < target:
        dur = int(rng.uniform(0.02, 0.06) * SR)
        start = int(rng.integers(0, max(1, n - dur)))
        if rng.random() < 0.5:
            out[start : start + dur] = 0.0
        else:
            # Repeat previous segment (PLC-ish).
            prev = max(0, start - dur)
            out[start : start + dur] = out[prev : prev + dur]
        lost += dur
    return out


def mild_reverb(audio: NDArray[np.floating], rng: np.random.Generator) -> NDArray[np.floating]:
    t60 = float(rng.uniform(0.12, 0.45))
    tau = t60 / (3.0 * np.log(10.0))
    n_rir = int(SR * min(0.6, t60 * 1.5))
    t = np.arange(n_rir) / SR
    rir = np.exp(-t / tau).astype(np.float64)
    rir[0] = 1.0
    wet = np.convolve(audio.astype(np.float64), rir, mode="full")[: audio.size]
    peak = np.max(np.abs(wet)) + 1e-12
    return (wet / peak * 0.9).astype(np.float32)


def random_gain(audio: NDArray[np.floating], rng: np.random.Generator) -> NDArray[np.floating]:
    g = float(10 ** (rng.uniform(-6.0, 6.0) / 20.0))
    return np.clip(audio.astype(np.float32) * g, -1.0, 1.0)


def mild_pitch_shift(audio: NDArray[np.floating], rng: np.random.Generator) -> NDArray[np.floating]:
    """±2% via resampling (cheap; not a phase vocoder)."""
    factor = float(rng.uniform(0.98, 1.02))
    n_out = max(1, int(round(audio.size / factor)))
    x = np.linspace(0.0, 1.0, audio.size)
    stretched = np.interp(np.linspace(0.0, 1.0, n_out), x, audio)
    if stretched.size >= audio.size:
        return stretched[: audio.size].astype(np.float32)
    return np.pad(stretched, (0, audio.size - stretched.size)).astype(np.float32)


class Augmentor:
    """Stochastic training augmentations with codec cache."""

    def __init__(
        self,
        *,
        p: float = 0.7,
        use_codec: bool = True,
        cache_dir: Optional[Path] = None,
        seed: int = 0,
    ) -> None:
        self.p = float(p)
        self.use_codec = use_codec
        self.cache_dir = cache_dir or Path("datasets/_codec_cache")
        self.rng = np.random.default_rng(seed)

    def __call__(
        self,
        audio: NDArray[np.floating],
        sr: int = SR,
        *,
        src_path: Optional[Path] = None,
    ) -> NDArray[np.floating]:
        out = np.asarray(audio, dtype=np.float32).reshape(-1)
        if self.rng.random() > self.p:
            return out
        if self.use_codec and self.rng.random() < 0.7:
            variant = str(self.rng.choice(CODEC_VARIANTS))
            try:
                out = codec_roundtrip(out, sr, variant, cache_dir=self.cache_dir, src_path=src_path)
            except Exception:
                pass  # keep unaugmented on ffmpeg failure
        if self.rng.random() < 0.5:
            out = add_noise(out, self.rng)
        if self.rng.random() < 0.4:
            out = packet_loss(out, self.rng)
        if self.rng.random() < 0.35:
            out = mild_reverb(out, self.rng)
        if self.rng.random() < 0.5:
            out = random_gain(out, self.rng)
        if self.rng.random() < 0.35:
            out = mild_pitch_shift(out, self.rng)
        return out.astype(np.float32)


def prewarm_codec_cache(
    paths: list[Path],
    cache_dir: Path,
    *,
    variants: tuple[str, ...] = CODEC_VARIANTS,
    limit: Optional[int] = None,
) -> int:
    """Pre-encode codec variants for a file list (run once before multi-epoch training)."""
    n = 0
    for i, path in enumerate(paths):
        if limit is not None and i >= limit:
            break
        audio = load_audio(path, SR)
        for v in variants:
            codec_roundtrip(audio, SR, v, cache_dir=cache_dir, src_path=path)
            n += 1
    return n
