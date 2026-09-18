from __future__ import annotations

import time

import numpy as np
import pytest
from scipy.signal import butter, filtfilt

from app.modules import phase as phase_mod
from app.modules import spectral as spectral_mod
from app.modules.spectral import extract, reset_cqt_cache
from app.types import ChannelProfile


def _sine(freq: float, sr: int = 16000, seconds: float = 2.0, amp: float = 0.5) -> np.ndarray:
    t = np.arange(int(sr * seconds), dtype=np.float64) / sr
    return (amp * np.sin(2.0 * np.pi * freq * t)).astype(np.float32)


def _white_noise(sr: int = 16000, seconds: float = 2.0, seed: int = 0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return rng.standard_normal(int(sr * seconds)).astype(np.float32) * 0.2


def test_sine_centroid_near_tone_and_low_flatness() -> None:
    reset_cqt_cache()
    audio = _sine(1000.0)
    feats = extract(audio, 16000, ChannelProfile.WEBRTC_WIDEBAND, force_cqt=True)
    assert feats["available"] is True
    assert abs(feats["spectral_centroid"] - 1000.0) < 80.0
    assert feats["spectral_flatness"] < 0.15


def test_white_noise_high_flatness() -> None:
    reset_cqt_cache()
    feats = extract(_white_noise(), 16000, ChannelProfile.VOIP_WIDEBAND, force_cqt=True)
    assert feats["spectral_flatness"] > 0.4


def test_lowpass_rolloff_near_cutoff() -> None:
    reset_cqt_cache()
    sr = 16000
    noise = _white_noise(sr=sr)
    b, a = butter(4, 2000.0 / (sr / 2.0), btype="low")
    low = filtfilt(b, a, noise).astype(np.float32)
    feats = extract(low, sr, ChannelProfile.WEBRTC_WIDEBAND, force_cqt=True)
    assert 1500.0 < feats["spectral_rolloff_85"] < 2800.0


def test_narrowband_returns_none_not_zero_for_high_bands() -> None:
    reset_cqt_cache()
    feats = extract(_sine(800.0), 16000, ChannelProfile.PSTN_NARROWBAND, force_cqt=True)
    assert feats["band_energy_4_6k"] is None
    assert feats["band_energy_6_8k"] is None
    assert feats["high_band_ratio_gt_4k"] is None
    assert feats["band_energy_4_6k"] is not 0.0  # type: ignore[comparison-overlap]
    assert "4 kHz" in feats["band_energy_4_6k_reason"]
    assert feats["high_band_available"] is False
    # Narrowband high_band_ratio is E(>2.8k)/E(total) — a real number within 4 kHz.
    assert isinstance(feats["high_band_ratio"], float)
    assert feats["band_energy_0_1k"] is not None


def test_wideband_computes_high_bands() -> None:
    reset_cqt_cache()
    feats = extract(_white_noise(), 16000, ChannelProfile.WEBRTC_WIDEBAND, force_cqt=True)
    assert feats["high_band_available"] is True
    assert isinstance(feats["band_energy_4_6k"], float)
    assert isinstance(feats["band_energy_6_8k"], float)
    assert isinstance(feats["high_band_ratio_gt_4k"], float)
    assert feats["high_band_ratio_gt_4k"] > 0.0


def test_determinism() -> None:
    reset_cqt_cache()
    audio = _sine(440.0)
    a = extract(audio, 16000, ChannelProfile.WEBRTC_WIDEBAND, force_cqt=True)
    reset_cqt_cache()
    b = extract(audio, 16000, ChannelProfile.WEBRTC_WIDEBAND, force_cqt=True)
    for key in a:
        if isinstance(a[key], float):
            assert a[key] == pytest.approx(b[key], rel=0, abs=1e-9), key
        else:
            assert a[key] == b[key], key


def test_lfcc_keys_present() -> None:
    reset_cqt_cache()
    feats = extract(_sine(500.0), 16000, ChannelProfile.VOIP_WIDEBAND, force_cqt=True)
    for i in range(20):
        assert f"lfcc_{i:02d}" in feats
        assert f"lfcc_delta_{i:02d}" in feats
        assert f"lfcc_delta2_{i:02d}" in feats


def test_combined_spectral_phase_under_60ms() -> None:
    reset_cqt_cache()
    audio = _white_noise(seconds=2.0)
    # Warm-up + one CQT refresh (production computes CQT every 4th window).
    extract(audio, 16000, ChannelProfile.WEBRTC_WIDEBAND, force_cqt=True)
    phase_mod.extract(audio, 16000)

    times = []
    for _ in range(8):
        t0 = time.perf_counter()
        # Carry-forward path — no force_cqt; cache already populated.
        spectral_mod.extract(audio, 16000, ChannelProfile.WEBRTC_WIDEBAND, force_cqt=False)
        phase_mod.extract(audio, 16000)
        times.append((time.perf_counter() - t0) * 1000.0)
    # Windows without a fresh CQT must stay under the 60 ms budget.
    # Skip any tick that lands on the every-4th CQT refresh (tick % 4 == 1 after warm-up).
    carry = [t for i, t in enumerate(times) if ((i + 2) % 4) != 1]
    if not carry:
        carry = times
    median_ms = float(np.median(carry))
    assert median_ms < 60.0, f"combined extract median {median_ms:.1f} ms >= 60 ms (times={times})"
