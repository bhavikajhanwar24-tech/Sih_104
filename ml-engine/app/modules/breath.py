"""Breath / micro-behaviour detection (Context §10.3).

Humans produce roughly 8–20 breath events per minute in conversational speech.
Zero breaths across 20+ seconds of continuous speech is a strong synthetic indicator.
``breath_absence_score`` is only meaningful after >= 15 s of cumulative speech.
"""

from __future__ import annotations

from typing import Any, Optional

import librosa
import numpy as np
from numpy.typing import NDArray

from app.config import settings

_FRAME = 512
_HOP = 256
_CENTROID_LO = 500.0
_CENTROID_HI = 2500.0
_DUR_LO_S = 0.150
_DUR_HI_S = 0.600
_PRE_PHON_GAP_S = 0.350  # breath ending within this before voiced onset counts as pre-phonatory


def _frame_features(audio: NDArray[np.floating], sr: int) -> dict[str, NDArray[np.floating]]:
    y = audio.astype(np.float32)
    rms = librosa.feature.rms(y=y, frame_length=_FRAME, hop_length=_HOP)[0]
    cent = librosa.feature.spectral_centroid(y=y, sr=sr, n_fft=_FRAME, hop_length=_HOP)[0]
    flat = librosa.feature.spectral_flatness(y=y, n_fft=_FRAME, hop_length=_HOP)[0]
    f0, voiced_flag, _ = librosa.pyin(
        y,
        fmin=60.0,
        fmax=400.0,
        sr=sr,
        frame_length=2048,
        hop_length=_HOP,
    )
    n = min(rms.size, cent.size, flat.size, len(f0))
    voiced = np.asarray(voiced_flag[:n], dtype=bool) & np.isfinite(f0[:n])
    return {
        "rms": rms[:n].astype(np.float64),
        "centroid": cent[:n].astype(np.float64),
        "flatness": flat[:n].astype(np.float64),
        "voiced": voiced.astype(bool),
    }


def _runs(mask: NDArray[np.bool_]) -> list[tuple[int, int]]:
    """Return inclusive-exclusive (start, end) frame index runs where mask is True."""
    out: list[tuple[int, int]] = []
    i = 0
    n = mask.size
    while i < n:
        if not mask[i]:
            i += 1
            continue
        j = i + 1
        while j < n and mask[j]:
            j += 1
        out.append((i, j))
        i = j
    return out


def _slow_envelope(segment: NDArray[np.floating]) -> bool:
    """Breaths have a relatively slow attack/decay vs plosive bursts."""
    if segment.size < 8:
        return False
    env = np.abs(segment)
    # Smooth
    win = max(3, segment.size // 20)
    kernel = np.ones(win, dtype=np.float64) / win
    smooth = np.convolve(env, kernel, mode="same")
    peak_i = int(np.argmax(smooth))
    peak = float(smooth[peak_i]) + 1e-12
    # Rise/fall should occupy a non-trivial fraction of the segment (not a click).
    rise = peak_i / max(smooth.size - 1, 1)
    fall = (smooth.size - 1 - peak_i) / max(smooth.size - 1, 1)
    return 0.15 <= rise <= 0.85 and fall >= 0.15 and peak > 0


def detect_breaths(
    audio: np.ndarray,
    sr: int,
    *,
    cumulative_speech_s: float = 0.0,
) -> dict[str, Any]:
    """Detect breath-like events in ``audio``.

    Parameters
    ----------
    cumulative_speech_s:
        Session-level voiced/speech time so far. ``breath_absence_score`` stays
        ``available: False`` until this reaches ``settings.breath_min_cumulative_speech_s``.
    """
    samples = np.asarray(audio, dtype=np.float64).reshape(-1)
    if samples.size == 0 or sr <= 0:
        return {"available": False, "reason": "empty_audio", "breath_events": 0}

    peak = float(np.max(np.abs(samples)))
    if peak > 1e-8:
        samples = samples / peak

    feats = _frame_features(samples, sr)
    rms = feats["rms"]
    centroid = feats["centroid"]
    flatness = feats["flatness"]
    voiced = feats["voiced"]
    hop_s = _HOP / float(sr)
    duration_s = samples.size / float(sr)

    # Adaptive thresholds from unvoiced frames when possible (full-file percentiles
    # are dominated by voiced energy and wash out quiet breaths).
    # Voiced frames are already excluded; keep a wide unvoiced energy gate so a
    # normalized breath burst is not fragmented by a tight upper bound.
    unvoiced_rms = rms[~voiced] if np.any(~voiced) else rms
    silence_floor = float(np.percentile(unvoiced_rms, 20)) + 1e-12
    peak_rms = float(np.max(rms) + 1e-12)
    lo = max(silence_floor * 1.1, 0.025 * peak_rms)
    hi = 0.98 * peak_rms
    if hi <= lo:
        hi = lo * 4.0
    candidate = (~voiced) & (rms >= lo) & (rms <= hi)

    events: list[dict[str, float]] = []
    for start, end in _runs(candidate):
        dur = (end - start) * hop_s
        if dur < _DUR_LO_S or dur > _DUR_HI_S:
            continue
        cent_m = float(np.mean(centroid[start:end]))
        if not (_CENTROID_LO <= cent_m <= _CENTROID_HI):
            continue
        # Prefer broadband noise over tonal bursts: reject very peaky spectra.
        # (spectral_flatness is numerically fragile on short windows, so use crest factor.)
        frame_mags = rms[start:end]
        crest = float(np.max(frame_mags) / (np.mean(frame_mags) + 1e-12))
        if crest > 8.0:
            continue
        seg_start = start * _HOP
        seg_end = min(samples.size, end * _HOP + _FRAME)
        segment = samples[seg_start:seg_end]
        if not _slow_envelope(segment):
            continue
        events.append(
            {
                "start_s": float(start * hop_s),
                "end_s": float(end * hop_s),
                "duration_ms": float(dur * 1000.0),
                "centroid_hz": cent_m,
            }
        )

    # Pre-phonatory: breath ends shortly before a voiced onset.
    voiced_onsets = []
    for i in range(1, voiced.size):
        if voiced[i] and not voiced[i - 1]:
            voiced_onsets.append(i * hop_s)
    pre = 0
    for ev in events:
        end = ev["end_s"]
        if any(0.0 <= (onset - end) <= _PRE_PHON_GAP_S for onset in voiced_onsets):
            pre += 1
    pre_ratio = float(pre / len(events)) if events else 0.0

    n_events = len(events)
    per_min = float(n_events / max(duration_s, 1e-6) * 60.0)
    mean_dur = float(np.mean([e["duration_ms"] for e in events])) if events else 0.0
    time_since = (
        float((duration_s - events[-1]["end_s"]) * 1000.0) if events else float(duration_s * 1000.0)
    )

    out: dict[str, Any] = {
        "breath_events": n_events,
        "breath_events_per_min": per_min,
        "mean_breath_duration_ms": mean_dur,
        "time_since_last_breath_ms": time_since,
        "pre_phonatory_ratio": pre_ratio,
        "event_list": events,
    }

    min_cum = settings.breath_min_cumulative_speech_s
    if cumulative_speech_s < min_cum:
        out["available"] = False
        out["reason"] = (
            f"breath_absence_not_yet_meaningful: cumulative_speech_s={cumulative_speech_s:.2f} "
            f"< {min_cum:.1f}s"
        )
        out["breath_absence_score"] = None
    else:
        # Near-zero breath rate after enough speech → high absence score.
        # Map: 12/min → ~0, 0/min → 1 (human baseline 8–20/min, Context §10.3).
        out["available"] = True
        out["breath_absence_score"] = float(np.clip(1.0 - per_min / 12.0, 0.0, 1.0))

    return out
