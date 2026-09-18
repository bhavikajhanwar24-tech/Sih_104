from __future__ import annotations

import numpy as np

from app.modules.phase import extract


def _sine(freq: float, sr: int = 16000, seconds: float = 2.0) -> np.ndarray:
    t = np.arange(int(sr * seconds), dtype=np.float64) / sr
    return (0.5 * np.sin(2.0 * np.pi * freq * t)).astype(np.float32)


def _noise(sr: int = 16000, seconds: float = 2.0, seed: int = 1) -> np.ndarray:
    rng = np.random.default_rng(seed)
    return rng.standard_normal(int(sr * seconds)).astype(np.float32) * 0.25


def test_phase_available_and_keys() -> None:
    feats = extract(_sine(440.0), 16000)
    assert feats["available"] is True
    assert 0.0 <= feats["phase_entropy_mean"] <= 1.0
    assert "group_delay_mean" in feats
    assert "group_delay_std" in feats
    assert "group_delay_abs_mean" in feats
    assert "phase_entropy_0_1k" in feats


def test_noise_has_higher_phase_entropy_than_sine() -> None:
    sine = extract(_sine(1000.0), 16000)
    noise = extract(_noise(), 16000)
    # Pure tone → more coherent phase evolution; noise → higher randomness.
    assert noise["phase_entropy_mean"] > sine["phase_entropy_mean"]


def test_determinism() -> None:
    audio = _sine(700.0)
    a = extract(audio, 16000)
    b = extract(audio, 16000)
    for key, value in a.items():
        if isinstance(value, float):
            assert value == b[key]
        else:
            assert value == b[key]


def test_empty_audio() -> None:
    feats = extract(np.zeros(0, dtype=np.float32), 16000)
    assert feats["available"] is False
