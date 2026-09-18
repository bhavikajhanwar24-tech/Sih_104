from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import soundfile as sf

from app.modules.breath import detect_breaths

FIXTURES = Path(__file__).parent / "fixtures" / "breath"


def _synth_breath_burst(sr: int, duration_s: float, centroid_hz: float = 1200.0) -> np.ndarray:
    n = int(sr * duration_s)
    rng = np.random.default_rng(1)
    noise = rng.standard_normal(n)
    # Band-limit toward breathy centroid via FFT filter.
    spec = np.fft.rfft(noise)
    freqs = np.fft.rfftfreq(n, d=1.0 / sr)
    gain = np.exp(-0.5 * ((freqs - centroid_hz) / 700.0) ** 2)
    filtered = np.fft.irfft(spec * gain, n=n)
    # Slow attack/decay envelope.
    attack = int(0.25 * n)
    decay = int(0.35 * n)
    env = np.ones(n)
    env[:attack] = np.linspace(0.0, 1.0, attack)
    env[-decay:] = np.linspace(1.0, 0.0, decay)
    return (filtered * env).astype(np.float32)


def _synth_voiced(sr: int, duration_s: float, f0: float = 140.0) -> np.ndarray:
    t = np.arange(int(sr * duration_s)) / sr
    sig = 0.3 * np.sin(2 * np.pi * f0 * t)
    for h in range(2, 6):
        sig += (0.3 / h) * np.sin(2 * np.pi * f0 * h * t)
    return sig.astype(np.float32)


def _ensure_fixtures() -> list[dict]:
    """Build 5 hand-labelled clips if missing (checked into fixtures via generator)."""
    FIXTURES.mkdir(parents=True, exist_ok=True)
    labels_path = FIXTURES / "labels.json"
    if labels_path.exists():
        return json.loads(labels_path.read_text(encoding="utf-8"))

    sr = 16000
    clips = []
    # Clip designs: each has 1 labelled breath (except clip 5 has 2).
    specs = [
        {"name": "clip01.wav", "breaths": [(0.40, 0.35)], "voice_after": True},
        {"name": "clip02.wav", "breaths": [(0.55, 0.28)], "voice_after": True},
        {"name": "clip03.wav", "breaths": [(0.30, 0.40)], "voice_after": True},
        {"name": "clip04.wav", "breaths": [(0.70, 0.25)], "voice_after": False},
        {"name": "clip05.wav", "breaths": [(0.25, 0.30), (1.10, 0.35)], "voice_after": True},
    ]
    for spec in specs:
        parts: list[np.ndarray] = []
        cursor = 0.0
        labelled = []
        for start_s, dur in spec["breaths"]:
            # Silence / low noise before breath
            gap = max(0.0, start_s - cursor)
            if gap > 0:
                parts.append(np.zeros(int(sr * gap), dtype=np.float32))
                cursor += gap
            breath = 0.25 * _synth_breath_burst(sr, dur)
            parts.append(breath)
            labelled.append({"start_s": cursor, "end_s": cursor + dur})
            cursor += dur
            if spec["voice_after"]:
                voiced = _synth_voiced(sr, 0.45)
                parts.append(voiced)
                cursor += 0.45
        # Tail pad to ~2s
        if cursor < 2.0:
            parts.append(np.zeros(int(sr * (2.0 - cursor)), dtype=np.float32))
        audio = np.concatenate(parts)
        path = FIXTURES / spec["name"]
        sf.write(str(path), audio, sr)
        clips.append({"file": spec["name"], "breaths": labelled})
    labels_path.write_text(json.dumps(clips, indent=2), encoding="utf-8")
    return clips


def _overlap(a0: float, a1: float, b0: float, b1: float) -> bool:
    return max(a0, b0) < min(a1, b1)


def test_breath_recall_at_least_0_6() -> None:
    labels = _ensure_fixtures()
    hits = 0
    total = 0
    for clip in labels:
        audio, sr = sf.read(str(FIXTURES / clip["file"]), dtype="float32")
        det = detect_breaths(audio, int(sr), cumulative_speech_s=0.0)
        detected = det.get("event_list") or []
        for truth in clip["breaths"]:
            total += 1
            if any(
                _overlap(truth["start_s"], truth["end_s"], ev["start_s"], ev["end_s"])
                for ev in detected
            ):
                hits += 1
    recall = hits / max(total, 1)
    assert recall >= 0.6, f"breath recall {recall:.2f} (hits={hits}/{total}) < 0.6"


def test_absence_unavailable_before_15s() -> None:
    audio = np.zeros(16000, dtype=np.float32)
    out = detect_breaths(audio, 16000, cumulative_speech_s=5.0)
    assert out["available"] is False
    assert out["breath_absence_score"] is None


def test_absence_score_high_with_no_breaths_after_15s() -> None:
    # Continuous voiced, no breath bursts.
    audio = _synth_voiced(16000, 3.0)
    out = detect_breaths(audio, 16000, cumulative_speech_s=20.0)
    assert out["available"] is True
    assert out["breath_events"] == 0 or out["breath_events_per_min"] < 2.0
    assert out["breath_absence_score"] is not None
    assert out["breath_absence_score"] >= 0.7
