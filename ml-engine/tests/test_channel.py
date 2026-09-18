from __future__ import annotations

import shutil
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np

from app.modules.channel import (
    double_compression_score,
    estimate_t60,
    extract,
    noise_floor_analysis,
    reset_bandwidth_history,
)
from app.types import ChannelProfile

SR = 16000


def _ffmpeg() -> str:
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception as exc:  # pragma: no cover
        raise RuntimeError("ffmpeg not available") from exc


def _speech_like(sr: int = SR, seconds: float = 3.0, seed: int = 7) -> np.ndarray:
    """Harmonic bursts + broadband noise with hard pauses (T60 + codec tests)."""
    rng = np.random.default_rng(seed)
    n = int(sr * seconds)
    t = np.arange(n, dtype=np.float64) / sr
    audio = np.zeros(n, dtype=np.float64)
    f0 = 140.0
    for k in range(1, 8):
        audio += (0.28 / k) * np.sin(2.0 * np.pi * f0 * k * t)
    # Broadband content so codec brick-walls / MDCT quantisation are visible.
    audio += 0.12 * rng.standard_normal(n)
    env = np.zeros(n, dtype=np.float64)
    period = int(0.70 * sr)
    on = int(0.25 * sr)
    for start in range(0, n, period):
        end = min(start + on, n)
        env[start:end] = 1.0
    audio *= env
    peak = np.max(np.abs(audio)) + 1e-12
    return (audio / peak * 0.7).astype(np.float32)


def _write_wav(path: Path, audio: np.ndarray, sr: int = SR) -> None:
    samples = np.clip(audio, -1.0, 1.0)
    pcm = (samples * 32767.0).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def _read_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        n = w.getnframes()
        raw = w.readframes(n)
        pcm = np.frombuffer(raw, dtype=np.int16).astype(np.float64) / 32768.0
    return pcm.astype(np.float32), sr


def _mulaw_roundtrip(audio: np.ndarray, sr: int = SR) -> np.ndarray:
    ffmpeg = _ffmpeg()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        src = root / "src.wav"
        mid = root / "mulaw.wav"
        out = root / "out.wav"
        _write_wav(src, audio, sr)
        subprocess.run(
            [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src),
             "-ar", "8000", "-ac", "1", "-c:a", "pcm_mulaw", str(mid)],
            check=True,
        )
        subprocess.run(
            [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(mid),
             "-ar", str(sr), "-ac", "1", "-c:a", "pcm_s16le", str(out)],
            check=True,
        )
        out_audio, out_sr = _read_wav(out)
        assert out_sr == sr
        return out_audio


def _mp3_roundtrip(audio: np.ndarray, sr: int = SR) -> np.ndarray:
    ffmpeg = _ffmpeg()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        src = root / "src.wav"
        mp3 = root / "a.mp3"
        out = root / "out.wav"
        _write_wav(src, audio, sr)
        subprocess.run(
            [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src),
             "-ar", str(sr), "-ac", "1", "-c:a", "libmp3lame", "-b:a", "64k", str(mp3)],
            check=True,
        )
        subprocess.run(
            [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(mp3),
             "-ar", str(sr), "-ac", "1", "-c:a", "pcm_s16le", str(out)],
            check=True,
        )
        out_audio, out_sr = _read_wav(out)
        assert out_sr == sr
        return out_audio


def _mp3_then_mulaw(audio: np.ndarray, sr: int = SR) -> np.ndarray:
    """Double encode: lossy MP3 render then G.711 µ-law telephony."""
    return _mulaw_roundtrip(_mp3_roundtrip(audio, sr), sr)


def _exp_rir(t60_s: float, sr: int = SR, length_s: float = 1.0) -> np.ndarray:
    """Exponential-decay synthetic RIR with known T60 (pressure to −60 dB)."""
    n = int(sr * length_s)
    t = np.arange(n, dtype=np.float64) / sr
    # Pressure ~ exp(-t/tau); T60 = 3*ln(10)*tau.
    tau = t60_s / (3.0 * np.log(10.0))
    rir = np.exp(-t / tau)
    rir[0] = 1.0
    return rir.astype(np.float64)


def test_mulaw_raises_double_compression_vs_clean() -> None:
    clean = _speech_like()
    degraded = _mulaw_roundtrip(clean)
    s_clean = double_compression_score(clean, SR)["double_compression_score"]
    s_deg = double_compression_score(degraded, SR)["double_compression_score"]
    assert s_deg > s_clean + 0.05, f"expected rise: clean={s_clean:.3f} mulaw={s_deg:.3f}"


def test_double_encoded_higher_than_single() -> None:
    clean = _speech_like(seconds=3.5, seed=11)
    single = _mp3_roundtrip(clean)  # one lossy stage
    double = _mp3_then_mulaw(clean)  # MP3 render + telephony re-encode
    s1 = double_compression_score(single, SR)["double_compression_score"]
    s2 = double_compression_score(double, SR)["double_compression_score"]
    assert s2 > s1 + 0.05, f"expected double > single: single={s1:.3f} double={s2:.3f}"


def test_t60_synthetic_rir_300ms() -> None:
    # Low residual noise so free-decay tails are RIR-dominated.
    n = int(SR * 4.0)
    t = np.arange(n, dtype=np.float64) / SR
    harmonic = np.zeros(n, dtype=np.float64)
    for k in range(1, 6):
        harmonic += (0.35 / k) * np.sin(2.0 * np.pi * 140.0 * k * t)
    env = np.zeros(n, dtype=np.float64)
    period = int(0.70 * SR)
    on = int(0.25 * SR)
    for start in range(0, n, period):
        env[start : min(start + on, n)] = 1.0
    dry = (harmonic * env).astype(np.float64)
    dry = dry / (np.max(np.abs(dry)) + 1e-12) * 0.7
    rir = _exp_rir(0.300, SR, length_s=1.0)
    wet = np.convolve(dry, rir, mode="full")[: dry.size]
    wet = wet / (np.max(np.abs(wet)) + 1e-12) * 0.7
    out = estimate_t60(wet.astype(np.float32), SR)
    t60 = out["t60_ms"]
    assert t60 is not None
    # Recover known 300 ms within 30% → 210–390; acceptance window 200–400.
    assert 200.0 <= t60 <= 400.0, f"t60_ms={t60}"
    assert abs(t60 - 300.0) / 300.0 <= 0.30
    assert out["rir_plausible"] is True


def test_extract_keys_and_noise_floor() -> None:
    reset_bandwidth_history()
    audio = _speech_like()
    feats = extract(audio, SR, ChannelProfile.VOIP_WIDEBAND, session_id="t1")
    assert feats["available"] is True
    assert "t60_ms" in feats
    assert "double_compression_score" in feats
    assert 0.0 <= feats["double_compression_score"] <= 1.0
    assert "noise_floor_db" in feats
    assert "noise_floor_stationarity" in feats
    assert "effective_bandwidth_hz" in feats
    assert "dc_offset" in feats
    assert "clipping_ratio" in feats
    noise = noise_floor_analysis(audio, SR)
    assert noise["noise_floor_stationarity"] <= 1.0


def test_mid_call_bandwidth_change() -> None:
    reset_bandwidth_history()
    # Wideband-ish noise then brick-wall narrowband.
    rng = np.random.default_rng(0)
    wb = rng.standard_normal(SR).astype(np.float32) * 0.2
    # Simulate NB by low-pass via FFT zeroing.
    spec = np.fft.rfft(wb)
    freqs = np.fft.rfftfreq(wb.size, d=1.0 / SR)
    spec[freqs > 3400] = 0
    nb = np.fft.irfft(spec, n=wb.size).astype(np.float32)
    a = extract(wb, SR, ChannelProfile.WEBRTC_WIDEBAND, session_id="call-x")
    b = extract(nb, SR, ChannelProfile.PSTN_NARROWBAND, session_id="call-x")
    assert a["mid_call_bandwidth_change"] is False
    assert b["mid_call_bandwidth_change"] is True
