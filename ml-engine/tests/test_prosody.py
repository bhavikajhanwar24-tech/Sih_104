from __future__ import annotations

import numpy as np
import pytest

from app.modules.prosody import extract, unnaturalness_score


def _glottal_train(
    sr: int,
    duration_s: float,
    f0_hz: float,
    jitter_local: float,
    shimmer: float = 0.05,
    hnr_noise: float = 0.02,
) -> np.ndarray:
    """Synthesize voiced pulses with known local jitter.

    Alternating periods T*(1+d), T*(1-d) give
    jitter_local = mean(|ΔT|) / mean(T) = 2d.
    """
    d = jitter_local / 2.0
    t0 = 1.0 / f0_hz
    periods = []
    t = 0.0
    flip = False
    while t < duration_s:
        p = t0 * (1.0 + d) if flip else t0 * (1.0 - d)
        periods.append(p)
        t += p
        flip = not flip

    n = int(sr * duration_s)
    out = np.zeros(n, dtype=np.float64)
    rng = np.random.default_rng(0)
    pos = 0.0
    amp = 1.0
    for p in periods:
        idx = int(round(pos * sr))
        if 0 <= idx < n:
            # Soft pulse (not a Dirac) so Praat can track it.
            width = max(2, int(0.0008 * sr))
            for k in range(-width, width + 1):
                j = idx + k
                if 0 <= j < n:
                    out[j] += amp * np.exp(-0.5 * (k / (width / 2)) ** 2)
        amp = max(0.2, amp * (1.0 + shimmer * (1.0 if rng.random() > 0.5 else -1.0) * 0.5))
        # Keep shimmer mild around target mean.
        amp = 0.85 * amp + 0.15
        pos += p
    # Mild lowpass via moving average to add harmonic structure.
    kernel = np.hanning(21)
    kernel /= kernel.sum()
    out = np.convolve(out, kernel, mode="same")
    out += hnr_noise * rng.standard_normal(n)
    out = out / (np.max(np.abs(out)) + 1e-12)
    return out.astype(np.float32)


def test_synthetic_jitter_within_15_percent() -> None:
    target = 0.010  # 1.0% — mid human range (0.5–1.5%)
    audio = _glottal_train(16000, duration_s=2.5, f0_hz=140.0, jitter_local=target, shimmer=0.05)
    feats = extract(audio, 16000)
    assert feats["available"] is True, feats
    measured = feats["jitter_local"]
    assert measured is not None
    rel_err = abs(measured - target) / target
    assert rel_err <= 0.15, f"jitter {measured:.5f} vs target {target:.5f} rel_err={rel_err:.3f}"


def test_insufficient_voiced_returns_unavailable() -> None:
    # Near-silence — no voiced speech.
    audio = np.zeros(16000, dtype=np.float32)
    feats = extract(audio, 16000)
    assert feats["available"] is False
    assert "insufficient_voiced" in feats["reason"] or "empty" in feats.get("reason", "")
    assert "jitter_local" not in feats or feats.get("jitter_local") is None


def test_unnaturalness_elevated_for_too_clean() -> None:
    # Perfect tone: very low jitter/shimmer, high HNR-like cleanliness via score inputs.
    score = unnaturalness_score(
        jitter_local=0.001,  # too low
        shimmer_local=0.01,  # too low
        hnr_db=35.0,  # too clean
        f0_mean=120.0,
        f0_std=0.5,  # over-smooth
    )
    assert score > 0.4


def test_human_like_ranges_low_unnaturalness() -> None:
    score = unnaturalness_score(
        jitter_local=0.009,
        shimmer_local=0.05,
        hnr_db=20.0,
        f0_mean=140.0,
        f0_std=12.0,
    )
    assert score < 0.15


def test_determinism() -> None:
    audio = _glottal_train(16000, 2.0, 130.0, 0.01)
    a = extract(audio, 16000)
    b = extract(audio, 16000)
    assert a["available"] == b["available"]
    if a["available"]:
        assert a["jitter_local"] == pytest.approx(b["jitter_local"], rel=0, abs=1e-9)
        assert a["f0_mean"] == pytest.approx(b["f0_mean"], rel=0, abs=1e-6)
