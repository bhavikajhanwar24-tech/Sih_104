"""Spectral / vocoder-artifact features (Context §10.1, §10.4).

LFCC outperforms MFCC for anti-spoofing because spoofing artifacts concentrate in
high linear-frequency bands that mel-warping compresses away.
"""

from __future__ import annotations

from typing import Any, Optional

import numpy as np
from numpy.typing import NDArray
from scipy.fftpack import dct

from app.types import ChannelProfile

_N_FFT = 512
_HOP = 256
_N_LFCC = 20
_N_FILTERS = 26
_CQT_EVERY = 4
_NARROWBAND_REASON = (
    "PSTN_NARROWBAND Nyquist limit is 4 kHz; features above 4 kHz are undefined "
    "(Context §10.4). Returning None, never 0.0."
)

_cqt_state: dict[str, Any] = {"tick": 0, "features": None}
_fb_cache: dict[tuple[int, float], NDArray[np.floating]] = {}


def _is_narrowband(profile: ChannelProfile | str) -> bool:
    value = profile.value if isinstance(profile, ChannelProfile) else str(profile)
    return value == ChannelProfile.PSTN_NARROWBAND.value


def _band_plan(profile: ChannelProfile | str) -> tuple[list[tuple[float, float]], float, bool]:
    if _is_narrowband(profile):
        bands = [(0.0, 1000.0), (1000.0, 2000.0), (2000.0, 3000.0), (3000.0, 4000.0)]
        return bands, 2800.0, False
    bands = [(0.0, 1000.0), (1000.0, 4000.0), (4000.0, 6000.0), (6000.0, 8000.0)]
    return bands, 4000.0, True


def _rfft_stft(audio: NDArray[np.floating], n_fft: int = _N_FFT, hop: int = _HOP) -> NDArray[np.complexfloating]:
    """Hann-windowed STFT via numpy rfft (faster than librosa for this workload)."""
    window = np.hanning(n_fft).astype(np.float64)
    if audio.size < n_fft:
        pad = np.zeros(n_fft, dtype=np.float64)
        pad[: audio.size] = audio
        frames = pad[None, :]
    else:
        n_frames = 1 + (audio.size - n_fft) // hop
        frames = np.lib.stride_tricks.as_strided(
            audio,
            shape=(n_frames, n_fft),
            strides=(audio.strides[0] * hop, audio.strides[0]),
            writeable=False,
        )
    windowed = frames * window
    return np.fft.rfft(windowed, axis=1).T  # (freq, time)


def _fft_freqs(sr: int, n_fft: int = _N_FFT) -> NDArray[np.floating]:
    return np.fft.rfftfreq(n_fft, d=1.0 / sr)


def _band_energy(mag: NDArray[np.floating], freqs: NDArray[np.floating], lo: float, hi: float) -> float:
    mask = (freqs >= lo) & (freqs < hi)
    if not np.any(mask):
        return 0.0
    return float(np.sum(mag[mask, :] ** 2))


def _linear_filterbank(sr: int, n_fft: int, n_filters: int, fmax: float) -> NDArray[np.floating]:
    key = (sr, float(fmax))
    cached = _fb_cache.get(key)
    if cached is not None:
        return cached
    n_bins = n_fft // 2 + 1
    points = np.linspace(0.0, fmax, n_filters + 2)
    bin_freqs = np.linspace(0.0, float(sr) / 2.0, n_bins)
    fb = np.zeros((n_filters, n_bins), dtype=np.float64)
    for i in range(n_filters):
        left, center, right = points[i], points[i + 1], points[i + 2]
        rising = (bin_freqs >= left) & (bin_freqs <= center)
        falling = (bin_freqs > center) & (bin_freqs <= right)
        if center > left:
            fb[i, rising] = (bin_freqs[rising] - left) / (center - left)
        if right > center:
            fb[i, falling] = (right - bin_freqs[falling]) / (right - center)
    _fb_cache[key] = fb
    return fb


def _lfcc_from_mag(
    mag: NDArray[np.floating],
    sr: int,
    profile: ChannelProfile | str,
) -> NDArray[np.floating]:
    # LFCC: linear-freq filterbank + log + DCT. Prefer LFCC over MFCC for anti-spoofing
    # because spoofing artifacts concentrate in high linear-frequency bands that
    # mel-warping compresses away.
    fmax = 4000.0 if _is_narrowband(profile) else min(8000.0, float(sr) / 2.0)
    fb = _linear_filterbank(sr, _N_FFT, _N_FILTERS, fmax)
    n = min(fb.shape[1], mag.shape[0])
    power = (mag[:n, :] ** 2)
    filtered = fb[:, :n] @ power
    log_spec = np.log(np.maximum(filtered, 1e-10))
    return dct(log_spec, type=2, axis=0, norm="ortho")[:_N_LFCC, :].astype(np.float64)


def _delta(coeffs: NDArray[np.floating], width: int = 2) -> NDArray[np.floating]:
    """Simple local regression delta (librosa-compatible enough for means)."""
    if coeffs.shape[1] < 2:
        return np.zeros_like(coeffs)
    padded = np.pad(coeffs, ((0, 0), (width, width)), mode="edge")
    denom = 2.0 * sum(i * i for i in range(1, width + 1))
    out = np.zeros_like(coeffs)
    for i in range(1, width + 1):
        out += i * (padded[:, width + i : width + i + coeffs.shape[1]] - padded[:, width - i : width - i + coeffs.shape[1]])
    return out / denom


def _spectral_stats(mag: NDArray[np.floating], freqs: NDArray[np.floating]) -> dict[str, float]:
    power = mag**2
    # Avoid all-zero frames.
    frame_power = np.sum(power, axis=0) + 1e-12
    # Centroid
    centroid = np.sum(freqs[:, None] * power, axis=0) / frame_power
    # Bandwidth
    bandwidth = np.sqrt(np.sum(((freqs[:, None] - centroid) ** 2) * power, axis=0) / frame_power)
    # Flatness (geometric / arithmetic mean of magnitude)
    log_mag = np.log(np.maximum(mag, 1e-12))
    geo = np.exp(np.mean(log_mag, axis=0))
    arith = np.mean(mag, axis=0) + 1e-12
    flatness = geo / arith
    # Rolloff
    cumsum = np.cumsum(power, axis=0)
    total = cumsum[-1, :] + 1e-12

    def rolloff(pct: float) -> float:
        thresh = pct * total
        idx = np.argmax(cumsum >= thresh, axis=0)
        return float(np.mean(freqs[idx]))

    # Flux
    if mag.shape[1] > 1:
        diff = np.diff(mag, axis=1)
        flux = float(np.mean(np.sqrt(np.sum(diff**2, axis=0))))
    else:
        flux = 0.0
    return {
        "spectral_centroid": float(np.mean(centroid)),
        "spectral_rolloff_85": rolloff(0.85),
        "spectral_rolloff_95": rolloff(0.95),
        "spectral_flatness": float(np.mean(flatness)),
        "spectral_bandwidth": float(np.mean(bandwidth)),
        "spectral_flux": flux,
    }


def _stft_periodicity(mag: NDArray[np.floating], hop: int = _HOP) -> dict[str, float]:
    """Cheap stand-in / carry path: autocorrelation of STFT frame energy."""
    envelope = np.mean(mag, axis=0).astype(np.float64)
    envelope = envelope - np.mean(envelope)
    if envelope.size < 8:
        return {"cqt_periodicity_256": 0.0, "cqt_periodicity_512": 0.0}
    acf = np.correlate(envelope, envelope, mode="full")
    acf = acf[acf.size // 2 :]
    if acf[0] > 0:
        acf = acf / acf[0]

    def peak_at_samples(samples: int) -> float:
        lag = int(round(samples / hop))
        lag = max(1, lag)
        lo = max(1, lag - 1)
        hi = min(acf.size, lag + 2)
        return float(np.max(acf[lo:hi])) if lo < hi else 0.0

    return {
        "cqt_periodicity_256": peak_at_samples(256),
        "cqt_periodicity_512": peak_at_samples(512),
    }


def _cqt_periodicity(audio: NDArray[np.floating], sr: int) -> dict[str, float]:
    """CQT frame-energy periodicity (expensive — only every _CQT_EVERY windows)."""
    import librosa

    hop = 512
    cqt = np.abs(
        librosa.cqt(
            audio.astype(np.float32, copy=False),
            sr=sr,
            hop_length=hop,
            fmin=65.0,
            n_bins=24,
            bins_per_octave=12,
        )
    )
    return _stft_periodicity(cqt, hop=hop)


def _maybe_cqt(
    audio: NDArray[np.floating],
    sr: int,
    mag: NDArray[np.floating],
    force: bool = False,
    *,
    skip_cqt: bool = False,
) -> dict[str, float]:
    global _cqt_state
    _cqt_state["tick"] = int(_cqt_state["tick"]) + 1
    tick = int(_cqt_state["tick"])
    cached = _cqt_state["features"]
    if skip_cqt:
        # Latency shed: reuse STFT periodicity only (Context §8.1 / 120 ms budget).
        return dict(_stft_periodicity(mag))
    refresh = force or cached is None or (tick % _CQT_EVERY == 1)
    if refresh:
        try:
            cached = _cqt_periodicity(audio, sr)
        except Exception:
            # Fall back to STFT energy periodicity if CQT fails / is unavailable.
            cached = _stft_periodicity(mag)
        _cqt_state["features"] = cached
    assert cached is not None
    return dict(cached)


def reset_cqt_cache() -> None:
    """Test helper: force the next extract() to recompute CQT."""
    global _cqt_state
    _cqt_state = {"tick": 0, "features": None}


def extract(
    audio: np.ndarray,
    sr: int,
    profile: ChannelProfile | str,
    *,
    force_cqt: bool = False,
    skip_cqt: bool = False,
) -> dict[str, Any]:
    """Extract spectral features for one window. Always sets available=True when audio is usable."""
    samples = np.asarray(audio, dtype=np.float64).reshape(-1)
    if samples.size == 0 or sr <= 0:
        return {"available": False, "reason": "empty_audio"}

    samples = np.ascontiguousarray(samples, dtype=np.float64)
    samples = samples - float(np.mean(samples))
    peak = float(np.max(np.abs(samples)))
    if peak > 1e-8:
        samples = samples / peak
        samples = np.ascontiguousarray(samples)

    stft = _rfft_stft(samples)
    mag_full = np.abs(stft)
    freqs_full = _fft_freqs(sr)

    narrow = _is_narrowband(profile)
    if narrow:
        keep = freqs_full <= 4000.0 + 1e-6
        mag = mag_full[keep, :]
        freqs = freqs_full[keep]
    else:
        mag = mag_full
        freqs = freqs_full

    stats = _spectral_stats(mag, freqs)
    bands, high_cut, high_available = _band_plan(profile)
    total_e = float(np.sum(mag**2)) + 1e-12
    band_energies: dict[str, Optional[float]] = {}
    reasons: dict[str, str] = {}

    if narrow:
        labels = ["0_1k", "1_2k", "2_3k", "3_4k"]
        for (lo, hi), label in zip(bands, labels):
            band_energies[f"band_energy_{label}"] = _band_energy(mag, freqs, lo, hi) / total_e
        band_energies["band_energy_4_6k"] = None
        band_energies["band_energy_6_8k"] = None
        reasons["band_energy_4_6k_reason"] = _NARROWBAND_REASON
        reasons["band_energy_6_8k_reason"] = _NARROWBAND_REASON
        reasons["wideband_high_band_reason"] = _NARROWBAND_REASON
        out_high_gt4: Optional[float] = None
    else:
        labels = ["0_1k", "1_4k", "4_6k", "6_8k"]
        for (lo, hi), label in zip(bands, labels):
            band_energies[f"band_energy_{label}"] = _band_energy(mag, freqs, lo, hi) / total_e
        out_high_gt4 = float(_band_energy(mag, freqs, 4000.0, float(freqs[-1]) + 1.0) / total_e)

    high_band_ratio = float(_band_energy(mag, freqs, high_cut, float(freqs[-1]) + 1.0) / total_e)

    lfcc = _lfcc_from_mag(mag_full, sr, profile)
    d1 = _delta(lfcc)
    d2 = _delta(d1)
    lfcc_mean = np.mean(lfcc, axis=1)
    d1_mean = np.mean(d1, axis=1)
    d2_mean = np.mean(d2, axis=1)

    out: dict[str, Any] = {"available": True}
    out.update(stats)
    for i in range(_N_LFCC):
        out[f"lfcc_{i:02d}"] = float(lfcc_mean[i])
        out[f"lfcc_delta_{i:02d}"] = float(d1_mean[i])
        out[f"lfcc_delta2_{i:02d}"] = float(d2_mean[i])
    out.update(band_energies)
    out.update(reasons)
    out["high_band_ratio"] = high_band_ratio
    out["high_band_available"] = bool(high_available)
    if narrow:
        out["high_band_ratio_gt_4k"] = None
        out["high_band_ratio_gt_4k_reason"] = _NARROWBAND_REASON
    else:
        out["high_band_ratio_gt_4k"] = out_high_gt4

    out.update(_maybe_cqt(samples.astype(np.float32), sr, mag, force=force_cqt, skip_cqt=skip_cqt))
    return out
