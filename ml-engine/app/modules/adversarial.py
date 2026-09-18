"""Defensive robustness perturbations (Context §5.1 A7 / §16.2).

This module ONLY perturbs existing audio to evaluate detector degradation.
It does NOT generate, improve, or synthesize voice clones / RVC / TTS.

Allowed: additive noise, pitch/time warps, codec round-trips, reverb, breath-like
insertions, packet loss, band-limiting — all applied to an input buffer the caller
already holds.
"""

from __future__ import annotations

import math
from dataclasses import asdict, dataclass, fields
from typing import Any, Optional

import numpy as np
from numpy.typing import NDArray
from scipy import signal
from scipy.signal import fftconvolve

# Explicit deny-list documentation — never implement these here.
FORBIDDEN_CAPABILITIES = (
    "voice_clone_generation",
    "rvc_voice_conversion",
    "tts_synthesis",
    "speaker_embedding_transfer",
)


@dataclass
class PerturbationConfig:
    """Live-adjustable evasion intensities. ``enabled=False`` is a no-op."""

    enabled: bool = False
    # Additive noise: target SNR in dB (5–30). None = off.
    noise_snr_db: Optional[float] = None
    # Pitch shift in percent (−5 … +5).
    pitch_shift_pct: float = 0.0
    # Time stretch factor (0.90 … 1.10). 1.0 = off.
    time_stretch: float = 1.0
    # Codec round-trip: None | "g711_ulaw" | "g711_alaw" | "amr_nb_sim"
    codec: Optional[str] = None
    # Synthetic room reverb T60 in ms (0 = off, typical 50–600).
    reverb_t60_ms: float = 0.0
    # Insert synthetic breath-like bursts per minute (0 = off).
    breath_rate_per_min: float = 0.0
    # Random packet loss percentage (0–30).
    packet_loss_pct: float = 0.0
    # Low-pass cutoff Hz (None = off; 4000 ≈ PSTN Nyquist).
    band_limit_hz: Optional[float] = None

    def clamp(self) -> "PerturbationConfig":
        snr = self.noise_snr_db
        if snr is not None:
            snr = float(np.clip(snr, 5.0, 30.0))
        codec = self.codec
        if codec is not None and codec not in {"g711_ulaw", "g711_alaw", "amr_nb_sim"}:
            codec = None
        band = self.band_limit_hz
        if band is not None:
            band = float(max(300.0, band))
        return PerturbationConfig(
            enabled=bool(self.enabled),
            noise_snr_db=snr,
            pitch_shift_pct=float(np.clip(self.pitch_shift_pct, -5.0, 5.0)),
            time_stretch=float(np.clip(self.time_stretch, 0.90, 1.10)),
            codec=codec,
            reverb_t60_ms=float(np.clip(self.reverb_t60_ms, 0.0, 800.0)),
            breath_rate_per_min=float(np.clip(self.breath_rate_per_min, 0.0, 30.0)),
            packet_loss_pct=float(np.clip(self.packet_loss_pct, 0.0, 30.0)),
            band_limit_hz=band,
        )

    def to_dict(self) -> dict[str, Any]:
        return asdict(self.clamp())

    @classmethod
    def from_dict(cls, data: dict[str, Any] | None) -> "PerturbationConfig":
        if not data:
            return cls()
        known = {f.name for f in fields(cls)}
        kwargs = {k: v for k, v in data.items() if k in known}
        return cls(**kwargs).clamp()


def apply(
    audio: NDArray[np.floating],
    sr: int,
    config: PerturbationConfig | dict[str, Any] | None,
    *,
    rng: Optional[np.random.Generator] = None,
) -> NDArray[np.float32]:
    """Apply configured perturbations. Returns float32 mono in [-1, 1]."""
    cfg = (
        config
        if isinstance(config, PerturbationConfig)
        else PerturbationConfig.from_dict(config)
    ).clamp()
    y = np.asarray(audio, dtype=np.float32).reshape(-1).copy()
    if y.size == 0 or not cfg.enabled:
        return y
    rng = rng or np.random.default_rng()

    if cfg.noise_snr_db is not None:
        y = _add_noise(y, cfg.noise_snr_db, rng)
    if abs(cfg.pitch_shift_pct) > 1e-3:
        y = _pitch_shift(y, sr, cfg.pitch_shift_pct)
    if abs(cfg.time_stretch - 1.0) > 1e-3:
        y = _time_stretch(y, cfg.time_stretch)
    if cfg.codec:
        y = _codec_roundtrip(y, sr, cfg.codec)
    if cfg.reverb_t60_ms > 1.0:
        y = _reverb(y, sr, cfg.reverb_t60_ms)
    if cfg.breath_rate_per_min > 0.1:
        y = _insert_breaths(y, sr, cfg.breath_rate_per_min, rng)
    if cfg.packet_loss_pct > 0.1:
        y = _packet_loss(y, sr, cfg.packet_loss_pct, rng)
    if cfg.band_limit_hz is not None:
        y = _band_limit(y, sr, cfg.band_limit_hz)

    peak = float(np.max(np.abs(y))) if y.size else 0.0
    if peak > 1.0:
        y = y / peak
    return np.clip(y, -1.0, 1.0).astype(np.float32, copy=False)


def _add_noise(y: NDArray[np.float32], snr_db: float, rng: np.random.Generator) -> NDArray[np.float32]:
    power = float(np.mean(y.astype(np.float64) ** 2))
    if power < 1e-12:
        noise = rng.standard_normal(y.size).astype(np.float32) * 0.01
        return y + noise
    snr_lin = 10.0 ** (snr_db / 10.0)
    noise_power = power / snr_lin
    noise = rng.standard_normal(y.size).astype(np.float32) * math.sqrt(noise_power)
    return (y + noise).astype(np.float32)


def _pitch_shift(y: NDArray[np.float32], sr: int, pct: float) -> NDArray[np.float32]:
    try:
        import librosa

        n_steps = 12.0 * math.log2(1.0 + pct / 100.0)
        out = librosa.effects.pitch_shift(y=y, sr=sr, n_steps=n_steps)
        return out.astype(np.float32)
    except Exception:
        # Fallback: crude resample-as-pitch (still no clone synthesis).
        factor = 1.0 + pct / 100.0
        n = max(1, int(round(y.size / factor)))
        x_old = np.linspace(0.0, 1.0, y.size, endpoint=False)
        x_new = np.linspace(0.0, 1.0, n, endpoint=False)
        stretched = np.interp(x_new, x_old, y).astype(np.float32)
        if stretched.size >= y.size:
            return stretched[: y.size]
        out = np.zeros(y.size, dtype=np.float32)
        out[: stretched.size] = stretched
        return out


def _time_stretch(y: NDArray[np.float32], rate: float) -> NDArray[np.float32]:
    try:
        import librosa

        out = librosa.effects.time_stretch(y=y, rate=rate)
        # Preserve original length for streaming frames.
        if out.size == y.size:
            return out.astype(np.float32)
        if out.size > y.size:
            return out[: y.size].astype(np.float32)
        pad = np.zeros(y.size, dtype=np.float32)
        pad[: out.size] = out
        return pad
    except Exception:
        n = max(1, int(round(y.size / rate)))
        x_old = np.linspace(0.0, 1.0, y.size, endpoint=False)
        x_new = np.linspace(0.0, 1.0, n, endpoint=False)
        stretched = np.interp(x_new, x_old, y).astype(np.float32)
        out = np.zeros(y.size, dtype=np.float32)
        m = min(out.size, stretched.size)
        out[:m] = stretched[:m]
        return out


def _mu_law_encode(x: NDArray[np.float32], mu: float = 255.0) -> NDArray[np.float32]:
    x = np.clip(x, -1.0, 1.0)
    return np.sign(x) * np.log1p(mu * np.abs(x)) / np.log1p(mu)


def _mu_law_decode(y: NDArray[np.float32], mu: float = 255.0) -> NDArray[np.float32]:
    return np.sign(y) * (1.0 / mu) * ((1.0 + mu) ** np.abs(y) - 1.0)


def _codec_roundtrip(y: NDArray[np.float32], sr: int, codec: str) -> NDArray[np.float32]:
    if codec == "g711_ulaw":
        enc = _mu_law_encode(y)
        # Quantise to 8-bit μ-law space then decode (double compression signature).
        q = np.round(enc * 127.0) / 127.0
        return _mu_law_decode(q.astype(np.float32)).astype(np.float32)
    if codec == "g711_alaw":
        # A-law-ish: similar companding with different curve — use μ-law + requantise.
        enc = _mu_law_encode(y, mu=87.6)
        q = np.round(enc * 127.0) / 127.0
        return _mu_law_decode(q.astype(np.float32), mu=87.6).astype(np.float32)
    if codec == "amr_nb_sim":
        # Honest simulation without linking AMR encoder: band-limit to 3.4 kHz + 4-bit quant.
        limited = _band_limit(y, sr, 3400.0)
        q = np.round(limited * 7.0) / 7.0
        return q.astype(np.float32)
    return y


def _reverb(y: NDArray[np.float32], sr: int, t60_ms: float) -> NDArray[np.float32]:
    t60 = max(0.01, t60_ms / 1000.0)
    n = int(sr * min(t60 * 1.5, 0.8))
    if n < 8:
        return y
    t = np.arange(n, dtype=np.float32) / float(sr)
    # Exponential decay IR with light noise texture (room tone), not a clone.
    decay = np.exp(-6.91 * t / t60).astype(np.float32)
    ir = decay * (0.15 * np.random.default_rng(7).standard_normal(n).astype(np.float32))
    ir[0] = 1.0
    wet = fftconvolve(y, ir, mode="full")[: y.size].astype(np.float32)
    return (0.7 * y + 0.3 * wet).astype(np.float32)


def _insert_breaths(
    y: NDArray[np.float32],
    sr: int,
    rate_per_min: float,
    rng: np.random.Generator,
) -> NDArray[np.float32]:
    dur_s = y.size / float(sr)
    n_breaths = max(1, int(round(rate_per_min * dur_s / 60.0)))
    out = y.copy()
    breath_len = int(0.22 * sr)
    for _ in range(n_breaths):
        start = int(rng.integers(0, max(1, out.size - breath_len)))
        t = np.arange(breath_len, dtype=np.float32) / float(sr)
        env = np.sin(np.pi * t / 0.22).astype(np.float32)
        # Band-limited noise ≈ breath spectrum (not a recorded human clone).
        noise = rng.standard_normal(breath_len).astype(np.float32)
        b, a = signal.butter(2, [500.0 / (sr / 2), 2200.0 / (sr / 2)], btype="band")
        breath = signal.lfilter(b, a, noise).astype(np.float32) * env * 0.08
        out[start : start + breath_len] += breath
    return out


def _packet_loss(
    y: NDArray[np.float32],
    sr: int,
    loss_pct: float,
    rng: np.random.Generator,
) -> NDArray[np.float32]:
    frame = max(1, int(0.02 * sr))  # 20 ms "packets"
    out = y.copy()
    n_frames = out.size // frame
    drop = rng.random(n_frames) < (loss_pct / 100.0)
    for i, d in enumerate(drop):
        if d:
            out[i * frame : (i + 1) * frame] = 0.0
    return out


def _band_limit(y: NDArray[np.float32], sr: int, cutoff_hz: float) -> NDArray[np.float32]:
    nyq = sr / 2.0
    wn = min(0.99, cutoff_hz / nyq)
    if wn <= 0.05:
        return y
    b, a = signal.butter(4, wn, btype="low")
    return signal.lfilter(b, a, y).astype(np.float32)
