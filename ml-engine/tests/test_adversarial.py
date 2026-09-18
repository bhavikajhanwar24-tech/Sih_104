"""Unit tests for defensive adversarial perturbations — no clone generation."""

from __future__ import annotations

import numpy as np

from app.modules import adversarial as adv


def _tone(sr: int = 16000, seconds: float = 0.5) -> np.ndarray:
    t = np.arange(int(sr * seconds), dtype=np.float32) / float(sr)
    return (0.3 * np.sin(2 * np.pi * 180.0 * t)).astype(np.float32)


def test_forbidden_capabilities_documented():
    assert "voice_clone_generation" in adv.FORBIDDEN_CAPABILITIES
    assert "rvc_voice_conversion" in adv.FORBIDDEN_CAPABILITIES


def test_disabled_is_noop():
    y = _tone()
    out = adv.apply(y, 16000, {"enabled": False, "noise_snr_db": 10})
    assert np.allclose(out, y)


def test_noise_changes_signal():
    y = _tone()
    out = adv.apply(
        y,
        16000,
        {"enabled": True, "noise_snr_db": 10},
        rng=np.random.default_rng(0),
    )
    assert out.shape == y.shape
    assert not np.allclose(out, y)
    assert float(np.max(np.abs(out))) <= 1.0 + 1e-5


def test_packet_loss_and_band_limit():
    y = _tone(seconds=1.0)
    out = adv.apply(
        y,
        16000,
        {
            "enabled": True,
            "packet_loss_pct": 20,
            "band_limit_hz": 3400,
        },
        rng=np.random.default_rng(1),
    )
    assert out.dtype == np.float32
    assert out.size == y.size


def test_no_clone_generation_api():
    assert not hasattr(adv, "generate_clone")
    assert not hasattr(adv, "voice_convert")
    assert not hasattr(adv, "rvc_convert")
    assert "voice_clone_generation" in adv.FORBIDDEN_CAPABILITIES
