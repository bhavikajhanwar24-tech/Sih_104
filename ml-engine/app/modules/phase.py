"""Phase features (Context §10.2).

Honest caveat: phase-based detection was strong against 2019-era vocoders and is
weakening against modern diffusion/flow vocoders. This feature is evidence, not proof.
Human phonation is micro-turbulent (chaotic phase); many older vocoders reconstruct
minimum-phase speech and leave entropy too low. Treat these scores as supporting
evidence inside the fusion engine — never as a standalone authenticity verdict.
"""

from __future__ import annotations

from typing import Any

import numpy as np
from numpy.typing import NDArray

_N_FFT = 512
_HOP = 256
_N_BINS = 32
_ENTROPY_NORM = float(np.log2(_N_BINS))

_BANDS = (
    ("0_1k", 0.0, 1000.0),
    ("1_4k", 1000.0, 4000.0),
    ("4_6k", 4000.0, 6000.0),
    ("6_8k", 6000.0, 8000.0),
)


def _rfft_stft(audio: NDArray[np.floating]) -> NDArray[np.complexfloating]:
    window = np.hanning(_N_FFT).astype(np.float64)
    if audio.size < _N_FFT:
        pad = np.zeros(_N_FFT, dtype=np.float64)
        pad[: audio.size] = audio
        frames = pad[None, :]
    else:
        n_frames = 1 + (audio.size - _N_FFT) // _HOP
        frames = np.lib.stride_tricks.as_strided(
            audio,
            shape=(n_frames, _N_FFT),
            strides=(audio.strides[0] * _HOP, audio.strides[0]),
            writeable=False,
        )
    return np.fft.rfft(frames * window, axis=1).T


def _phase_entropy(delta_phi: NDArray[np.floating]) -> float:
    flat = np.asarray(delta_phi, dtype=np.float64).reshape(-1)
    if flat.size == 0:
        return 0.0
    wrapped = (flat + np.pi) % (2.0 * np.pi) - np.pi
    hist, _ = np.histogram(wrapped, bins=_N_BINS, range=(-np.pi, np.pi), density=False)
    total = float(np.sum(hist))
    if total <= 0:
        return 0.0
    p = hist.astype(np.float64) / total
    p = p[p > 0]
    ent = float(-np.sum(p * np.log2(p)))
    return float(np.clip(ent / _ENTROPY_NORM, 0.0, 1.0))


def _modified_group_delay(X: NDArray[np.complexfloating], Y: NDArray[np.complexfloating]) -> tuple[float, float, float]:
    xr, xi = np.real(X), np.imag(X)
    yr, yi = np.real(Y), np.imag(Y)
    mag2 = xr * xr + xi * xi
    gamma = 1e-4 * float(np.mean(mag2) + 1e-12)
    tau = (xr * yr + xi * yi) / (mag2 + gamma)
    tau = np.clip(tau, -50.0, 50.0)
    return float(np.mean(tau)), float(np.std(tau)), float(np.mean(np.abs(tau)))


def extract(audio: np.ndarray, sr: int) -> dict[str, Any]:
    """Extract instantaneous-phase-deviation entropy and modified group-delay stats."""
    samples = np.asarray(audio, dtype=np.float64).reshape(-1)
    if samples.size == 0 or sr <= 0:
        return {"available": False, "reason": "empty_audio"}

    samples = np.ascontiguousarray(samples, dtype=np.float64)
    samples = samples - float(np.mean(samples))
    peak = float(np.max(np.abs(samples)))
    if peak > 1e-8:
        samples = samples / peak
        samples = np.ascontiguousarray(samples)

    X = _rfft_stft(samples)
    n = np.arange(samples.size, dtype=np.float64)
    Y = _rfft_stft(n * samples)

    phase = np.angle(X)
    unwrapped = np.unwrap(phase, axis=1)
    delta = np.diff(unwrapped, axis=1) if unwrapped.shape[1] >= 2 else np.zeros_like(unwrapped)

    freqs = np.fft.rfftfreq(_N_FFT, d=1.0 / sr)
    nyquist = float(sr) / 2.0

    band_entropies: list[float] = []
    out: dict[str, Any] = {"available": True}
    for name, lo, hi in _BANDS:
        if lo >= nyquist:
            out[f"phase_entropy_{name}"] = None
            out[f"phase_entropy_{name}_reason"] = (
                f"band {lo:.0f}-{hi:.0f} Hz exceeds Nyquist {nyquist:.0f} Hz"
            )
            continue
        hi_eff = min(hi, nyquist)
        mask = (freqs >= lo) & (freqs < hi_eff)
        if not np.any(mask) or delta.size == 0:
            out[f"phase_entropy_{name}"] = None
            out[f"phase_entropy_{name}_reason"] = "insufficient_bins"
            continue
        value = _phase_entropy(delta[mask, :])
        out[f"phase_entropy_{name}"] = value
        band_entropies.append(value)

    out["phase_entropy_mean"] = float(np.mean(band_entropies)) if band_entropies else 0.0
    gd_mean, gd_std, gd_abs = _modified_group_delay(X, Y)
    out["group_delay_mean"] = gd_mean
    out["group_delay_std"] = gd_std
    out["group_delay_abs_mean"] = gd_abs
    return out
