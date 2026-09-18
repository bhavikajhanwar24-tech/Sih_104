"""Prosody & micro-behaviour features (Context §10.3).

Uses Praat via parselmouth for jitter / shimmer / HNR when available — those are the
field-reference algorithms. A librosa fallback exists behind ``settings.use_parselmouth``.
"""

from __future__ import annotations

import logging
from typing import Any, Optional

import librosa
import numpy as np
from numpy.typing import NDArray

from app.config import settings

logger = logging.getLogger("sentinelvoice.ml.prosody")

try:
    import parselmouth
    from parselmouth.praat import call

    _HAS_PARSELMOUTH = True
except Exception:  # pragma: no cover - optional native dep
    parselmouth = None  # type: ignore[assignment]
    call = None  # type: ignore[assignment]
    _HAS_PARSELMOUTH = False


def _use_praat() -> bool:
    return bool(settings.use_parselmouth and _HAS_PARSELMOUTH)


def _entropy_of_diffs(values: NDArray[np.floating], bins: int = 32) -> float:
    if values.size < 2:
        return 0.0
    diffs = np.diff(values.astype(np.float64))
    hist, _ = np.histogram(diffs, bins=bins)
    total = float(np.sum(hist))
    if total <= 0:
        return 0.0
    p = hist.astype(np.float64) / total
    p = p[p > 0]
    return float(-np.sum(p * np.log2(p)) / np.log2(bins))


def _pyin(audio: NDArray[np.floating], sr: int) -> tuple[NDArray[np.floating], NDArray[np.bool_], float]:
    f0, voiced_flag, _ = librosa.pyin(
        audio.astype(np.float32),
        fmin=60.0,
        fmax=400.0,
        sr=sr,
        frame_length=2048,
        hop_length=256,
    )
    voiced = np.asarray(voiced_flag, dtype=bool) & np.isfinite(f0)
    hop_s = 256.0 / float(sr)
    voiced_s = float(np.sum(voiced) * hop_s)
    return np.asarray(f0, dtype=np.float64), voiced, voiced_s


def _praat_voice_measures(audio: NDArray[np.floating], sr: int) -> dict[str, Optional[float]]:
    """Jitter / shimmer / HNR via Praat PointProcess + Harmonicity."""
    assert parselmouth is not None and call is not None
    snd = parselmouth.Sound(audio.astype(np.float64), sampling_frequency=sr)
    pitch = call(snd, "To Pitch", 0.0, 60.0, 400.0)
    point_process = call([snd, pitch], "To PointProcess (cc)")
    # Praat returns fractions (e.g. 0.01 = 1%).
    jitter_local = float(call(point_process, "Get jitter (local)", 0, 0, 0.0001, 0.02, 1.3))
    jitter_rap = float(call(point_process, "Get jitter (rap)", 0, 0, 0.0001, 0.02, 1.3))
    jitter_ppq5 = float(call(point_process, "Get jitter (ppq5)", 0, 0, 0.0001, 0.02, 1.3))
    shimmer_local = float(
        call([snd, point_process], "Get shimmer (local)", 0, 0, 0.0001, 0.02, 1.3, 1.6)
    )
    shimmer_apq3 = float(
        call([snd, point_process], "Get shimmer (apq3)", 0, 0, 0.0001, 0.02, 1.3, 1.6)
    )
    shimmer_apq5 = float(
        call([snd, point_process], "Get shimmer (apq5)", 0, 0, 0.0001, 0.02, 1.3, 1.6)
    )
    harmonicity = call(snd, "To Harmonicity (cc)", 0.01, 60.0, 0.1, 1.0)
    hnr = float(call(harmonicity, "Get mean", 0, 0))

    def _finite(x: float) -> Optional[float]:
        return float(x) if np.isfinite(x) else None

    return {
        "jitter_local": _finite(jitter_local),
        "jitter_rap": _finite(jitter_rap),
        "jitter_ppq5": _finite(jitter_ppq5),
        "shimmer_local": _finite(shimmer_local),
        "shimmer_apq3": _finite(shimmer_apq3),
        "shimmer_apq5": _finite(shimmer_apq5),
        "hnr_db": _finite(hnr),
        "backend": "parselmouth",  # type: ignore[dict-item]
    }


def _periods_from_f0(f0: NDArray[np.floating], voiced: NDArray[np.bool_], hop_s: float) -> NDArray[np.floating]:
    periods = []
    for i, (ok, hz) in enumerate(zip(voiced, f0)):
        if ok and hz and hz > 0:
            periods.append(1.0 / float(hz))
    return np.asarray(periods, dtype=np.float64)


def _librosa_voice_measures(
    audio: NDArray[np.floating],
    sr: int,
    f0: NDArray[np.floating],
    voiced: NDArray[np.bool_],
) -> dict[str, Optional[float]]:
    """Fallback when Praat is unavailable — approximate, clearly labelled."""
    hop_s = 256.0 / float(sr)
    periods = _periods_from_f0(f0, voiced, hop_s)
    if periods.size < 4:
        return {
            "jitter_local": None,
            "jitter_rap": None,
            "jitter_ppq5": None,
            "shimmer_local": None,
            "shimmer_apq3": None,
            "shimmer_apq5": None,
            "hnr_db": None,
            "backend": "librosa_fallback",  # type: ignore[dict-item]
        }
    # Local jitter ≈ mean |T_i - T_{i+1}| / mean(T)
    diffs = np.abs(np.diff(periods))
    mean_t = float(np.mean(periods)) + 1e-12
    jitter_local = float(np.mean(diffs) / mean_t)
    # RAP: relative average perturbation over 3 periods
    if periods.size >= 3:
        rap_num = np.mean(
            [
                np.abs(periods[i] - np.mean(periods[i - 1 : i + 2]))
                for i in range(1, periods.size - 1)
            ]
        )
        jitter_rap = float(rap_num / mean_t)
    else:
        jitter_rap = None
    if periods.size >= 5:
        ppq_num = np.mean(
            [
                np.abs(periods[i] - np.mean(periods[i - 2 : i + 3]))
                for i in range(2, periods.size - 2)
            ]
        )
        jitter_ppq5 = float(ppq_num / mean_t)
    else:
        jitter_ppq5 = None

    # Amplitude peaks near period marks (crude shimmer)
    env = np.abs(audio)
    frame = max(1, int(0.01 * sr))
    amps = []
    t = 0.0
    idx = 0
    while idx < periods.size:
        sample = int(round(t * sr))
        if 0 <= sample < env.size - frame:
            amps.append(float(np.max(env[sample : sample + frame])))
        t += float(periods[idx])
        idx += 1
    amps_a = np.asarray(amps, dtype=np.float64)
    if amps_a.size >= 3:
        shimmer_local = float(np.mean(np.abs(np.diff(amps_a))) / (np.mean(amps_a) + 1e-12))
    else:
        shimmer_local = None
    shimmer_apq3 = shimmer_local
    shimmer_apq5 = shimmer_local

    # Rough HNR via harmonic / residual energy using F0 mean.
    voiced_hz = f0[voiced]
    if voiced_hz.size:
        f0_mean = float(np.mean(voiced_hz))
        # Band around F0 vs residual
        S = np.abs(np.fft.rfft(audio * np.hanning(audio.size)))
        freqs = np.fft.rfftfreq(audio.size, d=1.0 / sr)
        harm = 0.0
        for h in range(1, 6):
            target = f0_mean * h
            if target >= sr / 2:
                break
            band = (freqs > target - 30) & (freqs < target + 30)
            harm += float(np.sum(S[band] ** 2))
        total = float(np.sum(S**2)) + 1e-12
        noise = max(total - harm, 1e-12)
        hnr_db = float(10.0 * np.log10(harm / noise + 1e-12))
    else:
        hnr_db = None

    return {
        "jitter_local": jitter_local,
        "jitter_rap": jitter_rap,
        "jitter_ppq5": jitter_ppq5,
        "shimmer_local": shimmer_local,
        "shimmer_apq3": shimmer_apq3,
        "shimmer_apq5": shimmer_apq5,
        "hnr_db": hnr_db,
        "backend": "librosa_fallback",  # type: ignore[dict-item]
    }


def _syllable_nuclei(audio: NDArray[np.floating], sr: int, voiced: NDArray[np.bool_]) -> int:
    """Count energy peaks in voiced regions as a syllable-nucleus proxy."""
    hop = 256
    rms = librosa.feature.rms(y=audio.astype(np.float32), frame_length=1024, hop_length=hop)[0]
    n = min(rms.size, voiced.size)
    if n < 3:
        return 0
    env = rms[:n].copy()
    mask = voiced[:n]
    env[~mask] = 0.0
    if float(np.max(env)) <= 0:
        return 0
    thr = 0.3 * float(np.max(env))
    peaks = 0
    for i in range(1, n - 1):
        if env[i] >= thr and env[i] >= env[i - 1] and env[i] > env[i + 1]:
            peaks += 1
    return int(peaks)


def _pause_stats(
    voiced: NDArray[np.bool_],
    hop_s: float,
) -> dict[str, float]:
    """Pause = run of unvoiced frames between voiced regions."""
    pauses: list[float] = []
    i = 0
    n = voiced.size
    # Skip leading/trailing silence for pause definition.
    while i < n and not voiced[i]:
        i += 1
    while i < n:
        while i < n and voiced[i]:
            i += 1
        start = i
        while i < n and not voiced[i]:
            i += 1
        if i < n and start < i:  # internal pause (followed by more speech)
            pauses.append((i - start) * hop_s)
    if not pauses:
        return {
            "pause_count": 0.0,
            "pause_mean_s": 0.0,
            "pause_std_s": 0.0,
            "pause_uniformity": 0.0,
        }
    arr = np.asarray(pauses, dtype=np.float64)
    mean = float(np.mean(arr))
    std = float(np.std(arr))
    # Uniformity in [0,1]: low relative variance → high uniformity (synthetic tell).
    uniformity = float(np.clip(1.0 - (std / (mean + 1e-6)), 0.0, 1.0))
    return {
        "pause_count": float(arr.size),
        "pause_mean_s": mean,
        "pause_std_s": std,
        "pause_uniformity": uniformity,
    }


def unnaturalness_score(
    jitter_local: Optional[float],
    shimmer_local: Optional[float],
    hnr_db: Optional[float],
    f0_mean: Optional[float],
    f0_std: Optional[float],
) -> float:
    """Aggregate [0,1] penalty from deviation vs published human ranges.

    Ranges (configurable via Settings — do not hardcode callers):
      - jitter_local [0.005, 0.015]: Teixeira et al. / Baken & Orlikoff conversational norms
        (~0.5–1.5%); Context §10.3.
      - shimmer_local [0.03, 0.08]: typical local shimmer ~3–8%; Context §10.3.
      - HNR > 28 dB: human conversational often 15–25 dB; >28 is “too clean”; Context §10.3.
      - f0_std / f0_mean < f0_rel_std_min: over-smooth F0 micro-contour (synthetic tell).
    """
    penalties: list[float] = []

    j_lo, j_hi = settings.jitter_local_min, settings.jitter_local_max
    if jitter_local is not None:
        if jitter_local < j_lo:
            penalties.append(min(1.0, (j_lo - jitter_local) / j_lo))
        elif jitter_local > j_hi:
            penalties.append(min(1.0, (jitter_local - j_hi) / j_hi))

    s_lo, s_hi = settings.shimmer_local_min, settings.shimmer_local_max
    if shimmer_local is not None:
        if shimmer_local < s_lo:
            penalties.append(min(1.0, (s_lo - shimmer_local) / s_lo))
        elif shimmer_local > s_hi:
            penalties.append(min(1.0, (shimmer_local - s_hi) / s_hi))

    if hnr_db is not None and hnr_db > settings.hnr_too_clean_db:
        # Scale: 28→0, 40→~1
        penalties.append(min(1.0, (hnr_db - settings.hnr_too_clean_db) / 12.0))

    if f0_mean is not None and f0_std is not None and f0_mean > 1.0:
        rel = f0_std / f0_mean
        if rel < settings.f0_rel_std_min:
            penalties.append(min(1.0, (settings.f0_rel_std_min - rel) / settings.f0_rel_std_min))

    if not penalties:
        return 0.0
    return float(np.clip(np.mean(penalties), 0.0, 1.0))


def extract(audio: np.ndarray, sr: int) -> dict[str, Any]:
    """Extract prosodic features. Returns available=False if voiced speech < 0.5 s."""
    samples = np.asarray(audio, dtype=np.float64).reshape(-1)
    if samples.size == 0 or sr <= 0:
        return {"available": False, "reason": "empty_audio"}

    peak = float(np.max(np.abs(samples)))
    if peak > 1e-8:
        samples = samples / peak

    f0, voiced, voiced_s = _pyin(samples, sr)
    hop_s = 256.0 / float(sr)
    voiced_fraction = float(np.mean(voiced)) if voiced.size else 0.0

    if voiced_s < settings.min_voiced_seconds:
        return {
            "available": False,
            "reason": (
                f"insufficient_voiced_speech: {voiced_s:.3f}s < "
                f"{settings.min_voiced_seconds}s (jitter/shimmer undefined)"
            ),
            "voiced_seconds": voiced_s,
            "voiced_fraction": voiced_fraction,
        }

    voiced_f0 = f0[voiced]
    f0_mean = float(np.mean(voiced_f0))
    f0_std = float(np.std(voiced_f0))
    f0_range = float(np.max(voiced_f0) - np.min(voiced_f0))
    f0_contour_entropy = _entropy_of_diffs(voiced_f0)

    if _use_praat():
        try:
            voice = _praat_voice_measures(samples, sr)
        except Exception as exc:
            logger.warning("parselmouth_failed fallback=librosa error=%s", type(exc).__name__)
            voice = _librosa_voice_measures(samples, sr, f0, voiced)
    else:
        voice = _librosa_voice_measures(samples, sr, f0, voiced)

    nuclei = _syllable_nuclei(samples, sr, voiced)
    artic_rate = float(nuclei / max(voiced_s, 1e-6))
    pauses = _pause_stats(voiced, hop_s)

    score = unnaturalness_score(
        voice.get("jitter_local"),  # type: ignore[arg-type]
        voice.get("shimmer_local"),  # type: ignore[arg-type]
        voice.get("hnr_db"),  # type: ignore[arg-type]
        f0_mean,
        f0_std,
    )

    return {
        "available": True,
        "backend": voice.get("backend"),
        "f0_mean": f0_mean,
        "f0_std": f0_std,
        "f0_range": f0_range,
        "f0_contour_entropy": f0_contour_entropy,
        "jitter_local": voice.get("jitter_local"),
        "jitter_rap": voice.get("jitter_rap"),
        "jitter_ppq5": voice.get("jitter_ppq5"),
        "shimmer_local": voice.get("shimmer_local"),
        "shimmer_apq3": voice.get("shimmer_apq3"),
        "shimmer_apq5": voice.get("shimmer_apq5"),
        "hnr_db": voice.get("hnr_db"),
        "articulation_rate_syl_sec": artic_rate,
        "voiced_fraction": voiced_fraction,
        "voiced_seconds": voiced_s,
        "unnaturalness_score": score,
        **pauses,
    }
