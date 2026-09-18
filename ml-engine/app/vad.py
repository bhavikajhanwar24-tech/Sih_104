from __future__ import annotations

import numpy as np

try:
    import webrtcvad

    _vad = webrtcvad.Vad(2)
except Exception:  # pragma: no cover - missing/broken wheel (e.g. some 3.13 hosts)
    webrtcvad = None  # type: ignore[assignment]
    _vad = None

_FRAME_MS = 30
_RATE = 16000
_FRAME_SAMPLES = int(_RATE * _FRAME_MS / 1000)


def _to_int16_pcm(window: np.ndarray) -> bytes:
    clipped = np.clip(np.asarray(window, dtype=np.float32), -1.0, 1.0)
    return (clipped * 32767.0).astype(np.int16).tobytes()


def _frame_is_speech(frame: np.ndarray) -> bool:
    if frame.size < _FRAME_SAMPLES:
        return False
    pcm16 = _to_int16_pcm(frame[:_FRAME_SAMPLES])
    if _vad is not None:
        return bool(_vad.is_speech(pcm16, _RATE))
    rms = float(np.sqrt(np.mean(np.square(frame[:_FRAME_SAMPLES], dtype=np.float32))))
    return rms > 0.01


def is_speech(window: np.ndarray) -> bool:
    """True if a majority of 30 ms frames in ``window`` contain speech."""
    return speech_ratio(window) >= 0.5


def speech_ratio(window: np.ndarray) -> float:
    """Fraction of 30 ms frames classified as speech. Used to freeze EMA on silence."""
    samples = np.asarray(window, dtype=np.float32).reshape(-1)
    if samples.size < _FRAME_SAMPLES:
        return 0.0
    n_frames = samples.size // _FRAME_SAMPLES
    if n_frames == 0:
        return 0.0
    hits = 0
    for i in range(n_frames):
        start = i * _FRAME_SAMPLES
        if _frame_is_speech(samples[start : start + _FRAME_SAMPLES]):
            hits += 1
    return hits / n_frames
