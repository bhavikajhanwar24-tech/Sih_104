from __future__ import annotations

import numpy as np
import pytest

from app.normaliser import normalise
from app.types import ChannelProfile


def _sine(freq_hz: float, sr: int, seconds: float = 1.0, amp: float = 0.5) -> np.ndarray:
    t = np.arange(int(sr * seconds), dtype=np.float64) / sr
    return (amp * np.sin(2.0 * np.pi * freq_hz * t)).astype(np.float32)


def _to_s16le(pcm: np.ndarray) -> bytes:
    clipped = np.clip(pcm, -1.0, 1.0)
    return (clipped * 32767.0).astype("<i2").tobytes()


def _linear_to_ulaw(samples: np.ndarray) -> bytes:
    x = np.clip(np.asarray(samples, dtype=np.int32), -32768, 32767)
    sign = np.where(x < 0, 0x80, 0x00).astype(np.uint8)
    mag = np.abs(x).clip(0, 32635) + 0x84
    exponent = np.floor(np.log2(mag.astype(np.float64))).astype(np.int32) - 7
    exponent = np.clip(exponent, 0, 7)
    mantissa = (mag >> (exponent + 3)) & 0x0F
    encoded = np.bitwise_not(sign | ((exponent.astype(np.uint8) << 4) | mantissa.astype(np.uint8)))
    return encoded.astype(np.uint8).tobytes()


def _linear_to_alaw(samples: np.ndarray) -> bytes:
    x = np.clip(np.asarray(samples, dtype=np.int32), -32768, 32767)
    sign = np.where(x < 0, 0x80, 0x00).astype(np.int32)
    mag = np.abs(x).clip(0, 32635)
    exponent = np.zeros_like(mag)
    for e in range(7, 0, -1):
        exponent = np.where((exponent == 0) & (mag >= (256 << e)), e, exponent)
    mantissa = np.where(exponent == 0, mag >> 4, (mag >> (exponent + 3)) & 0x0F)
    encoded = (sign | (exponent << 4) | mantissa) ^ 0x55
    return encoded.astype(np.uint8).tobytes()


def test_mulaw_sine_roundtrip_snr() -> None:
    orig = _sine(440.0, 16000)
    pcm16 = np.clip(orig * 32767.0, -32768, 32767).astype(np.int16)
    raw = _linear_to_ulaw(pcm16)
    decoded, profile = normalise(raw, "mulaw", 16000, 1)
    assert profile is ChannelProfile.PSTN_NARROWBAND
    n = min(orig.size, decoded.size)
    signal = orig[:n]
    err = signal - decoded[:n]
    snr = 10.0 * np.log10(np.mean(signal**2) / np.mean(err**2))
    assert snr > 25.0


def test_8k_to_16k_preserves_1khz_peak_bin() -> None:
    tone = _sine(1000.0, 8000, seconds=1.0)
    out, profile = normalise(_to_s16le(tone), "pcm_s16le", 8000, 1)
    assert profile is ChannelProfile.PSTN_NARROWBAND
    windowed = out * np.hanning(out.size)
    spec = np.abs(np.fft.rfft(windowed))
    peak = int(np.argmax(spec))
    expected = int(round(1000.0 * out.size / 16000.0))
    assert abs(peak - expected) <= 2


def test_output_invariants_pcm_s16le() -> None:
    raw = _to_s16le(_sine(220.0, 16000, seconds=0.25))
    out, profile = normalise(raw, "pcm_s16le", 16000, 1, source="voip")
    assert out.dtype == np.float32
    assert out.ndim == 1
    assert out.size > 0
    assert float(out.min()) >= -1.0
    assert float(out.max()) <= 1.0
    assert profile is ChannelProfile.VOIP_WIDEBAND


def test_pcm_f32le_browser_is_webrtc() -> None:
    pcm = _sine(330.0, 48000, seconds=0.2)
    out, profile = normalise(pcm.astype("<f4").tobytes(), "pcm_f32le", 48000, 1, source="browser")
    assert out.dtype == np.float32
    assert float(np.max(np.abs(out))) <= 1.0
    assert abs(out.size / 16000.0 - 0.2) < 0.02
    assert profile is ChannelProfile.WEBRTC_WIDEBAND


def test_slin16_matches_pcm_s16le() -> None:
    tone = _sine(500.0, 8000, seconds=0.1)
    raw = _to_s16le(tone)
    a, _ = normalise(raw, "slin16", 8000, 1)
    b, _ = normalise(raw, "pcm_s16le", 8000, 1)
    np.testing.assert_allclose(a, b, atol=1e-6)


def test_stereo_downmix_averages_channels() -> None:
    n = 1600
    left = np.full(n, 0.4, dtype=np.float32)
    right = np.full(n, -0.2, dtype=np.float32)
    interleaved = np.empty(n * 2, dtype="<f4")
    interleaved[0::2] = left
    interleaved[1::2] = right
    out, _ = normalise(interleaved.tobytes(), "pcm_f32le", 16000, 2)
    np.testing.assert_allclose(out, 0.1, atol=1e-5)


def test_alaw_roundtrip_invariants() -> None:
    orig = _sine(700.0, 8000, seconds=0.5)
    raw = _linear_to_alaw(np.clip(orig * 32767.0, -32768, 32767).astype(np.int16))
    out, profile = normalise(raw, "alaw", 8000, 1)
    assert profile is ChannelProfile.PSTN_NARROWBAND
    assert out.dtype == np.float32
    assert float(np.max(np.abs(out))) <= 1.0
    assert out.size > 0


def test_explicit_profile_override() -> None:
    raw = _to_s16le(_sine(100.0, 16000, seconds=0.05))
    _, profile = normalise(
        raw,
        "pcm_s16le",
        16000,
        1,
        profile=ChannelProfile.PSTN_NARROWBAND,
        source="browser",
    )
    assert profile is ChannelProfile.PSTN_NARROWBAND


def test_unsupported_encoding_raises() -> None:
    with pytest.raises(ValueError, match="unsupported encoding"):
        normalise(b"\x00\x00", "opus", 16000, 1)
