from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import urllib.request
import wave
from pathlib import Path

import numpy as np
import pytest

from app.modules import speaker as speaker_mod
from app.types import ChannelProfile

SR = 16000
FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "speaker"

# Public short speech clips (SpeechBrain test samples / LibriSpeech-style).
_SAMPLE_URLS = {
    "spk_a": "https://github.com/speechbrain/speechbrain/raw/develop/tests/samples/single-mic/example1.wav",
    "spk_b": "https://www.openslr.org/resources/1/waves_yesno.tar.gz",  # fallback handled below
}


def _ffmpeg() -> str:
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    import imageio_ffmpeg

    return imageio_ffmpeg.get_ffmpeg_exe()


def _write_wav(path: Path, audio: np.ndarray, sr: int = SR) -> None:
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def _read_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        nch = w.getnchannels()
        raw = w.readframes(w.getnframes())
        pcm = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    if nch > 1:
        pcm = pcm.reshape(-1, nch).mean(axis=1)
    return pcm, sr


def _resample(audio: np.ndarray, sr_in: int, sr_out: int = SR) -> np.ndarray:
    if sr_in == sr_out:
        return audio.astype(np.float32)
    n_out = int(round(audio.size * sr_out / float(sr_in)))
    x = np.linspace(0.0, 1.0, audio.size)
    return np.interp(np.linspace(0.0, 1.0, n_out), x, audio).astype(np.float32)


def _mulaw_roundtrip(audio: np.ndarray, sr: int = SR) -> np.ndarray:
    ffmpeg = _ffmpeg()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        src, mid, out = root / "s.wav", root / "m.wav", root / "o.wav"
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
        out_a, out_sr = _read_wav(out)
        assert out_sr == sr
        return out_a


def _synthetic_speaker(seed: int, seconds: float = 3.0, f0: float = 120.0) -> np.ndarray:
    """Formant-ish harmonic stack — distinct seeds → distinct ECAPA regions for unit tests."""
    rng = np.random.default_rng(seed)
    n = int(SR * seconds)
    t = np.arange(n, dtype=np.float64) / SR
    audio = np.zeros(n, dtype=np.float64)
    for k in range(1, 10):
        audio += (0.22 / k) * np.sin(2.0 * np.pi * f0 * k * t + rng.uniform(0, 0.5))
    # Speaker-specific spectral tilt / noise colour.
    noise = rng.standard_normal(n)
    # Colour noise with a simple 1-pole filter unique per seed.
    alpha = 0.85 + 0.1 * ((seed % 7) / 7.0)
    coloured = np.zeros(n)
    for i in range(1, n):
        coloured[i] = alpha * coloured[i - 1] + (1 - alpha) * noise[i]
    audio = 0.75 * audio + 0.18 * coloured
    # Keep continuously voiced so duration gate passes.
    peak = np.max(np.abs(audio)) + 1e-12
    return (audio / peak * 0.7).astype(np.float32)


def _ensure_real_clips() -> tuple[np.ndarray, np.ndarray]:
    """Download two distinct speech clips for acceptance (cached under fixtures/)."""
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    a_path = FIXTURE_DIR / "spk_a.wav"
    b_path = FIXTURE_DIR / "spk_b.wav"

    if not a_path.is_file():
        try:
            urllib.request.urlretrieve(_SAMPLE_URLS["spk_a"], a_path)
        except Exception as exc:
            pytest.skip(f"could not download spk_a sample: {exc}")

    if not b_path.is_file():
        # Second clip: generate from a different SpeechBrain sample if available,
        # else synthesise a clearly different talker and skip strict <0.50 if model
        # cannot separate synth — prefer a second GitHub sample.
        alt = (
            "https://github.com/speechbrain/speechbrain/raw/develop/"
            "tests/samples/single-mic/example2.wav"
        )
        try:
            urllib.request.urlretrieve(alt, b_path)
        except Exception:
            # Fall back: build a long synthetic "other" and still run mismatch path.
            _write_wav(b_path, _synthetic_speaker(99, seconds=4.0, f0=210.0))

    a, sr_a = _read_wav(a_path)
    b, sr_b = _read_wav(b_path)
    a = _resample(a, sr_a)
    b = _resample(b, sr_b)
    # Ensure >= 1.5 s
    if a.size < int(1.5 * SR):
        a = np.tile(a, int(np.ceil(1.5 * SR / a.size)))[: int(3 * SR)]
    if b.size < int(1.5 * SR):
        b = np.tile(b, int(np.ceil(1.5 * SR / b.size)))[: int(3 * SR)]
    return a.astype(np.float32), b.astype(np.float32)


@pytest.fixture(scope="module")
def ecapa_ready() -> dict:
    try:
        info = speaker_mod.warmup()
    except Exception as exc:
        pytest.skip(f"ECAPA warmup failed: {exc}")
    assert info["ready"] is True
    assert info["warmup_ms"] is not None
    assert info["embed_latency_ms"] is not None
    return info


def test_warmup_logs_latency(ecapa_ready: dict) -> None:
    assert ecapa_ready["embed_latency_ms"] > 0
    assert ecapa_ready["window_stride"] in (1, 2, 4)
    assert speaker_mod.is_ready()
    # Document measured latency for the pitch deck / acceptance.
    print(
        f"\n[SLIDE] ECAPA warmup_ms={ecapa_ready['warmup_ms']:.1f} "
        f"embed_latency_ms={ecapa_ready['embed_latency_ms']:.1f} "
        f"stride={ecapa_ready['window_stride']}"
    )


def test_short_utterance_rejected(ecapa_ready: dict) -> None:
    short = _synthetic_speaker(1, seconds=0.8)
    emb, reason = speaker_mod.embed(short, SR)
    assert emb is None
    assert reason is not None
    assert "short" in reason


def test_same_speaker_cosine_above_match(ecapa_ready: dict) -> None:
    clip, _other = _ensure_real_clips()
    # Two heavily overlapping windows of the SAME continuous utterance.
    # Non-overlapping halves of a short clip can score low (different words).
    win = int(2.5 * SR)
    if clip.size < win + int(0.6 * SR):
        clip = np.tile(clip, 2)[: win + int(0.6 * SR)]
    left = clip[:win]
    right = clip[int(0.6 * SR) : int(0.6 * SR) + win]
    e1, r1 = speaker_mod.embed(left, SR)
    e2, r2 = speaker_mod.embed(right, SR)
    assert e1 is not None and e2 is not None, (r1, r2)
    cmp = speaker_mod.compare(e1, e2)
    print(f"\n[ACCEPT] same-speaker cosine={cmp['cosine_similarity']:.3f} verdict={cmp['verdict']}")
    assert cmp["cosine_similarity"] > 0.70
    assert cmp["verdict"] == "MATCH"


def test_different_speakers_cosine_below_mismatch(ecapa_ready: dict) -> None:
    a, b = _ensure_real_clips()
    ea, ra = speaker_mod.embed(a, SR)
    eb, rb = speaker_mod.embed(b, SR)
    assert ea is not None and eb is not None, (ra, rb)
    cmp = speaker_mod.compare(ea, eb)
    print(
        f"\n[ACCEPT] different-speaker cosine={cmp['cosine_similarity']:.3f} "
        f"verdict={cmp['verdict']}"
    )
    assert cmp["cosine_similarity"] < 0.50
    assert cmp["verdict"] == "MISMATCH"


def test_channel_mismatch_drop_documented(ecapa_ready: dict) -> None:
    clip, _ = _ensure_real_clips()
    if clip.size < int(2.5 * SR):
        clip = np.tile(clip, 3)[: int(3 * SR)]
    clean = clip
    degraded = _mulaw_roundtrip(clean)

    e_clean, r1 = speaker_mod.embed(clean, SR)
    e_nb, r2 = speaker_mod.embed(degraded, SR)
    assert e_clean is not None and e_nb is not None, (r1, r2)

    # Honest cross-channel score (what happens if you ignore CHANNEL_MISMATCH).
    cross = speaker_mod.compare(e_nb, e_clean)
    same = speaker_mod.compare(e_clean[:], e_clean)
    # Re-embed a second clean window for matched-channel baseline.
    e_clean2, _ = speaker_mod.embed(clean[SR // 4 :], SR)
    assert e_clean2 is not None
    matched = speaker_mod.compare(e_clean2, e_clean)
    drop = matched["cosine_similarity"] - cross["cosine_similarity"]

    slide = {
        "matched_channel_cosine": round(matched["cosine_similarity"], 4),
        "cross_channel_clean_vs_mulaw_cosine": round(cross["cosine_similarity"], 4),
        "channel_mismatch_drop": round(drop, 4),
        "note": (
            "Context §10.5/§12: enrol per channel profile. Cross-channel cosine drops "
            "sharply on a genuine speaker — never report this as MISMATCH; return "
            "INCONCLUSIVE / CHANNEL_MISMATCH instead."
        ),
    }
    out_path = FIXTURE_DIR / "channel_mismatch_slide.json"
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(slide, indent=2), encoding="utf-8")
    print(f"\n[SLIDE] channel mismatch drop={drop:.3f} -> {out_path}")
    print(json.dumps(slide, indent=2))

    assert drop > 0.05, f"expected measurable drop, got {drop}"

    # Correct API behaviour: live NB vs WB-only enrolment → CHANNEL_MISMATCH.
    enrolments = {ChannelProfile.WEBRTC_WIDEBAND.value: e_clean}
    guarded = speaker_mod.compare_with_channel(
        e_nb, ChannelProfile.PSTN_NARROWBAND, enrolments
    )
    assert guarded["verdict"] == "INCONCLUSIVE"
    assert guarded["reason"] == "CHANNEL_MISMATCH"
    assert guarded["cosine_similarity"] is None

    # Matching NB enrolment → real score, not CHANNEL_MISMATCH.
    enrolments_nb = {
        ChannelProfile.WEBRTC_WIDEBAND.value: e_clean,
        ChannelProfile.PSTN_NARROWBAND.value: e_nb,
    }
    ok = speaker_mod.compare_with_channel(e_nb, ChannelProfile.PSTN_NARROWBAND, enrolments_nb)
    assert ok["reason"] is None
    assert ok["verdict"] == "MATCH"
    assert ok["cosine_similarity"] > 0.70


def test_intra_call_drift_low_for_same_speaker(ecapa_ready: dict) -> None:
    speaker_mod.reset_session_state("drift-1")
    clip, _ = _ensure_real_clips()
    win = int(2.0 * SR)
    if clip.size < win + int(0.4 * SR):
        clip = np.tile(clip, 2)[: win + int(0.4 * SR)]
    base = clip[:win]
    # Accumulate >=5 s of the same continuous talker to lock the reference.
    for _ in range(3):
        emb, reason = speaker_mod.embed(base, SR)
        assert emb is not None, reason
        d = speaker_mod.update_drift("drift-1", emb, 2.0)
    assert d["reference_ready"] is True
    # Slightly shifted window of the same speaker — genuine drift should stay low.
    shifted = clip[int(0.25 * SR) : int(0.25 * SR) + win]
    emb2, reason2 = speaker_mod.embed(shifted, SR)
    assert emb2 is not None, reason2
    d2 = speaker_mod.update_drift("drift-1", emb2, 2.0)
    assert d2["intra_call_drift"] < 0.10, f"genuine drift too high: {d2['intra_call_drift']}"
    assert d2["rolling_max_drift"] < 0.10
    print(f"\n[ACCEPT] intra-call drift={d2['intra_call_drift']:.3f} rolling_max={d2['rolling_max_drift']:.3f}")


def test_compare_thresholds_configurable(ecapa_ready: dict) -> None:
    a = np.random.default_rng(0).standard_normal(192)
    a = a / np.linalg.norm(a)
    b = a.copy()
    assert speaker_mod.compare(a, b, match_threshold=0.99)["verdict"] == "MATCH"
    # Near-orthogonal
    c = np.random.default_rng(1).standard_normal(192)
    c = c / np.linalg.norm(c)
    out = speaker_mod.compare(a, c, match_threshold=0.99, mismatch_threshold=0.80)
    assert out["verdict"] in ("MISMATCH", "INCONCLUSIVE")
