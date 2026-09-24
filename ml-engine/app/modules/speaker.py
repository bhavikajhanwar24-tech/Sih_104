"""Speaker verification via ECAPA-TDNN (Context §10.5 / §12).

192-d embeddings from SpeechBrain ``spkrec-ecapa-voxceleb``, cosine vs enrolled
Voice Passport, and intra-call drift for RVC detection.

Channel mismatch is the #1 false-rejection cause: enrolled embeddings are stored
PER channel profile. Never compare a wideband enrolment against a narrowband live
window and report a misleading low score — return INCONCLUSIVE / CHANNEL_MISMATCH.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any, Optional

import numpy as np
from numpy.typing import NDArray

from app.config import settings
from app.types import ChannelProfile

logger = logging.getLogger("sentinelvoice.ml.speaker")

EMBED_DIM = 192
MIN_SPEECH_SECONDS = 1.5
REFERENCE_SECONDS = 5.0
MODEL_SOURCE = "speechbrain/spkrec-ecapa-voxceleb"
LATENCY_BUDGET_MS = 40.0

# Module-level singleton — load once in warmup(); never per window.
_classifier: Any = None
_warmup_ms: Optional[float] = None
_embed_latency_ms: Optional[float] = None
_window_stride: int = 1  # 1 = every window; 2/4 if over budget
_window_counter: dict[str, int] = {}
_last_emb: dict[str, NDArray[np.floating]] = {}


@dataclass
class DriftTracker:
    """Intra-call drift vs the first ~5 s reference embedding."""

    reference: Optional[NDArray[np.floating]] = None
    provisional: Optional[NDArray[np.floating]] = None
    speech_seconds_accumulated: float = 0.0
    rolling_max_drift: float = 0.0
    last_drift: float = 0.0


_drift: dict[str, DriftTracker] = {}


def reset_session_state(session_id: Optional[str] = None) -> None:
    """Clear per-session drift / stride caches (tests / session close)."""
    if session_id is None:
        _drift.clear()
        _window_counter.clear()
        _last_emb.clear()
    else:
        _drift.pop(session_id, None)
        _window_counter.pop(session_id, None)
        _last_emb.pop(session_id, None)


def is_ready() -> bool:
    return _classifier is not None


def warmup_ms() -> Optional[float]:
    return _warmup_ms


def embed_latency_ms() -> Optional[float]:
    return _embed_latency_ms


def window_stride() -> int:
    return _window_stride


def warmup(force: bool = False) -> dict[str, Any]:
    """Load ECAPA once at FastAPI startup; measure load + embed latency.

    If a 2 s embed exceeds ``LATENCY_BUDGET_MS`` (40 ms), set stride to 2 or 4 so
    the fast path embeds every Nth window and reuses the previous vector.
    """
    global _classifier, _warmup_ms, _embed_latency_ms, _window_stride

    if _classifier is not None and not force:
        return {
            "ready": True,
            "warmup_ms": _warmup_ms,
            "embed_latency_ms": _embed_latency_ms,
            "window_stride": _window_stride,
            "model": MODEL_SOURCE,
        }

    t0 = time.perf_counter()
    try:
        from speechbrain.inference.speaker import EncoderClassifier
    except Exception:
        from speechbrain.pretrained import EncoderClassifier  # type: ignore[attr-defined]

    # savedir keeps HF cache local to the process cwd / user cache.
    _classifier = EncoderClassifier.from_hparams(
        source=MODEL_SOURCE,
        run_opts={"device": "cpu"},
    )
    _warmup_ms = (time.perf_counter() - t0) * 1000.0
    logger.info(
        "speaker_ecapa_loaded model=%s warmup_ms=%.1f",
        MODEL_SOURCE,
        _warmup_ms,
    )

    # Latency probe: 2 s of noise (speech-like amplitude).
    probe = (0.1 * np.random.default_rng(0).standard_normal(settings.sample_rate * 2)).astype(
        np.float32
    )
    t1 = time.perf_counter()
    _ = _encode(probe, settings.sample_rate)
    _embed_latency_ms = (time.perf_counter() - t1) * 1000.0

    if _embed_latency_ms <= LATENCY_BUDGET_MS:
        _window_stride = 1
    elif _embed_latency_ms <= LATENCY_BUDGET_MS * 2.5:
        _window_stride = 2
    else:
        _window_stride = 4

    logger.info(
        "speaker_embed_latency_ms=%.1f budget_ms=%.1f window_stride=%d",
        _embed_latency_ms,
        LATENCY_BUDGET_MS,
        _window_stride,
    )
    return {
        "ready": True,
        "warmup_ms": _warmup_ms,
        "embed_latency_ms": _embed_latency_ms,
        "window_stride": _window_stride,
        "model": MODEL_SOURCE,
    }


def _encode(audio: NDArray[np.floating], sr: int) -> NDArray[np.floating]:
    global _classifier
    if _classifier is None:
        try:
            warmup()
        except Exception:
            logger.warning("speaker_classifier_unavailable — generating heuristic embedding")
            vec = np.zeros(EMBED_DIM, dtype=np.float64)
            vec[0] = 1.0
            return vec
    import torch

    samples = np.asarray(audio, dtype=np.float32).reshape(-1)
    if sr != 16000:
        # SpeechBrain ECAPA expects 16 kHz; resample via linear interp (light).
        n_out = int(round(samples.size * 16000 / float(sr)))
        x = np.linspace(0.0, 1.0, samples.size, dtype=np.float64)
        x_out = np.linspace(0.0, 1.0, n_out, dtype=np.float64)
        samples = np.interp(x_out, x, samples).astype(np.float32)
    wav = torch.from_numpy(samples).unsqueeze(0)
    with torch.inference_mode():
        emb = _classifier.encode_batch(wav)
    vec = emb.squeeze().detach().cpu().numpy().astype(np.float64)
    if vec.ndim > 1:
        vec = vec.reshape(-1)
    if vec.size != EMBED_DIM:
        # Some SpeechBrain builds return (1, 1, 192).
        vec = vec.reshape(-1)[:EMBED_DIM]
    norm = float(np.linalg.norm(vec)) + 1e-12
    return (vec / norm).astype(np.float64)


def _speech_duration_s(audio: NDArray[np.floating], sr: int) -> float:
    """Rough speech duration via energy gate (VAD-lite; avoid webrtcvad dependency here)."""
    samples = np.asarray(audio, dtype=np.float64).reshape(-1)
    if samples.size == 0:
        return 0.0
    frame = max(1, int(sr * 0.02))
    hop = frame
    n = 1 + max(0, (samples.size - frame) // hop)
    rms = np.empty(n, dtype=np.float64)
    for i in range(n):
        seg = samples[i * hop : i * hop + frame]
        rms[i] = np.sqrt(np.mean(seg**2) + 1e-20)
    thr = 0.25 * float(np.percentile(rms, 90) + 1e-12)
    return float(np.sum(rms >= thr) * hop / float(sr))


def embed(
    audio: NDArray[np.floating],
    sr: int,
) -> tuple[Optional[NDArray[np.floating]], Optional[str]]:
    """Return L2-normalised 192-d embedding, or (None, reason) if too short / unavailable.

    Requires >= 1.5 s of speech — short-utterance embeddings are unreliable and produce
    false mismatches on a genuine speaker (Context §10.5).
    """
    samples = np.asarray(audio, dtype=np.float32).reshape(-1)
    if samples.size == 0 or sr <= 0:
        return None, "empty_audio"
    speech_s = _speech_duration_s(samples, sr)
    # Also accept total duration when the whole clip is voiced (enrolment WAVs).
    total_s = samples.size / float(sr)
    effective = max(speech_s, total_s if speech_s >= 0.5 * total_s else speech_s)
    if effective < MIN_SPEECH_SECONDS and total_s < MIN_SPEECH_SECONDS:
        return None, f"speech_too_short:{effective:.2f}s<={MIN_SPEECH_SECONDS}s"
    if total_s < MIN_SPEECH_SECONDS:
        return None, f"utterance_too_short:{total_s:.2f}s<{MIN_SPEECH_SECONDS}s"
    if _classifier is None:
        return None, "model_not_loaded"
    try:
        return _encode(samples, sr), None
    except Exception as exc:  # pragma: no cover
        logger.exception("speaker_embed_failed")
        return None, f"embed_error:{type(exc).__name__}"


def cosine(a: NDArray[np.floating], b: NDArray[np.floating]) -> float:
    aa = np.asarray(a, dtype=np.float64).reshape(-1)
    bb = np.asarray(b, dtype=np.float64).reshape(-1)
    denom = (float(np.linalg.norm(aa)) * float(np.linalg.norm(bb))) + 1e-12
    return float(np.clip(np.dot(aa, bb) / denom, -1.0, 1.0))


def compare(
    live_emb: NDArray[np.floating],
    enrolled_emb: NDArray[np.floating],
    *,
    match_threshold: Optional[float] = None,
    mismatch_threshold: Optional[float] = None,
) -> dict[str, Any]:
    """Cosine compare live vs enrolled embedding → MATCH / INCONCLUSIVE / MISMATCH.

    Context §10.5: thresholds MUST be recalibrated on *your* data — do not hardcode a
    blog-post value. Channel mismatch shifts the operating point sharply; enrol over the
    same channel type you will verify on (wideband studio ≠ G.711 phone).
    Defaults: >0.70 MATCH, 0.50–0.70 INCONCLUSIVE, <0.50 MISMATCH.
    """
    match_thr = (
        float(settings.speaker_match_threshold) if match_threshold is None else float(match_threshold)
    )
    mismatch_thr = (
        float(settings.speaker_mismatch_threshold)
        if mismatch_threshold is None
        else float(mismatch_threshold)
    )
    sim = cosine(live_emb, enrolled_emb)
    if sim > match_thr:
        verdict = "MATCH"
    elif sim < mismatch_thr:
        verdict = "MISMATCH"
    else:
        verdict = "INCONCLUSIVE"
    return {
        "cosine_similarity": sim,
        "verdict": verdict,
        "match_threshold": match_thr,
        "mismatch_threshold": mismatch_thr,
    }


def resolve_enrolment(
    live_profile: ChannelProfile | str,
    enrolments: dict[str, NDArray[np.floating]],
) -> dict[str, Any]:
    """Pick the enrolment matching the live channel profile, or CHANNEL_MISMATCH.

    ``enrolments`` maps ChannelProfile.value → 192-d L2 embedding.
    """
    key = live_profile.value if isinstance(live_profile, ChannelProfile) else str(live_profile)
    if key in enrolments:
        return {"ok": True, "profile": key, "embedding": enrolments[key]}
    # Wideband aliases: VOIP and WEBRTC can share a wideband passport.
    wide = {ChannelProfile.VOIP_WIDEBAND.value, ChannelProfile.WEBRTC_WIDEBAND.value}
    if key in wide:
        for alt in wide:
            if alt in enrolments:
                return {"ok": True, "profile": alt, "embedding": enrolments[alt]}
    return {
        "ok": False,
        "verdict": "INCONCLUSIVE",
        "reason": "CHANNEL_MISMATCH",
        "live_profile": key,
        "enrolled_profiles": sorted(enrolments.keys()),
    }


def compare_with_channel(
    live_emb: NDArray[np.floating],
    live_profile: ChannelProfile | str,
    enrolments: dict[str, NDArray[np.floating]],
    *,
    match_threshold: Optional[float] = None,
    mismatch_threshold: Optional[float] = None,
) -> dict[str, Any]:
    """Compare using the matching per-profile enrolment, else CHANNEL_MISMATCH."""
    resolved = resolve_enrolment(live_profile, enrolments)
    if not resolved.get("ok"):
        return {
            "cosine_similarity": None,
            "verdict": "INCONCLUSIVE",
            "reason": "CHANNEL_MISMATCH",
            "live_profile": resolved.get("live_profile"),
            "enrolled_profiles": resolved.get("enrolled_profiles"),
        }
    out = compare(
        live_emb,
        resolved["embedding"],
        match_threshold=match_threshold,
        mismatch_threshold=mismatch_threshold,
    )
    out["enrolled_profile_used"] = resolved["profile"]
    out["reason"] = None
    return out


def update_drift(
    session_id: str,
    emb: NDArray[np.floating],
    window_speech_s: float,
) -> dict[str, Any]:
    """Update intra-call drift vs first-5s reference. drift = 1 - cosine.

    Genuine speakers typically drift < 0.10; real-time voice conversion often > 0.15
    (Context §10.5).
    """
    tracker = _drift.setdefault(session_id, DriftTracker())
    vec = np.asarray(emb, dtype=np.float64).copy()

    if tracker.reference is None:
        tracker.speech_seconds_accumulated += max(0.0, float(window_speech_s))
        tracker.provisional = vec
        if tracker.speech_seconds_accumulated >= REFERENCE_SECONDS:
            tracker.reference = vec
            return {
                "intra_call_drift": 0.0,
                "rolling_max_drift": tracker.rolling_max_drift,
                "reference_ready": True,
            }
        return {
            "intra_call_drift": 0.0,
            "rolling_max_drift": tracker.rolling_max_drift,
            "reference_ready": False,
        }

    sim = cosine(tracker.reference, vec)
    drift = float(1.0 - sim)
    tracker.last_drift = drift
    tracker.rolling_max_drift = max(tracker.rolling_max_drift, drift)
    return {
        "intra_call_drift": drift,
        "rolling_max_drift": tracker.rolling_max_drift,
        "reference_ready": True,
    }


def extract(
    audio: np.ndarray,
    sr: int,
    profile: ChannelProfile | str | None = None,
    *,
    session_id: Optional[str] = None,
    enrolled_embeddings: Optional[dict[str, NDArray[np.floating]]] = None,
    enrolled_profile_id: Optional[str] = None,
) -> dict[str, Any]:
    """Feature-module extract: embedding + optional passport compare + drift."""
    samples = np.asarray(audio, dtype=np.float32).reshape(-1)
    if samples.size == 0 or sr <= 0:
        return {"available": False, "reason": "empty_audio"}
    if _classifier is None:
        return {"available": False, "reason": "model_not_loaded"}

    sid = session_id or "_default"
    stride = max(1, _window_stride)
    count = _window_counter.get(sid, 0)
    _window_counter[sid] = count + 1

    emb: Optional[NDArray[np.floating]]
    reason: Optional[str] = None
    reused = False
    if count % stride == 0:
        emb, reason = embed(samples, sr)
        if emb is not None:
            _last_emb[sid] = emb
    else:
        emb = _last_emb.get(sid)
        reused = emb is not None
        if emb is None:
            emb, reason = embed(samples, sr)
            if emb is not None:
                _last_emb[sid] = emb

    if emb is None:
        return {"available": False, "reason": reason or "embed_failed", "window_stride": stride}

    speech_s = min(samples.size / float(sr), _speech_duration_s(samples, sr))
    if speech_s < 0.05:
        speech_s = samples.size / float(sr)
    drift = update_drift(sid, emb, speech_s)

    out: dict[str, Any] = {
        "available": True,
        "embedding": emb,
        "embedding_dim": int(emb.size),
        "embedding_reused": reused,
        "window_stride": stride,
        "intra_call_drift": drift["intra_call_drift"],
        "rolling_max_drift": drift["rolling_max_drift"],
        "cosine_similarity": None,
        "verdict": None,
        "enrolled_profile_id": enrolled_profile_id,
        "reason": None,
    }

    live_profile = profile or ChannelProfile.WEBRTC_WIDEBAND
    if enrolled_embeddings:
        cmp = compare_with_channel(emb, live_profile, enrolled_embeddings)
        out["cosine_similarity"] = cmp.get("cosine_similarity")
        out["verdict"] = cmp.get("verdict")
        out["reason"] = cmp.get("reason")
        out["enrolled_profile_used"] = cmp.get("enrolled_profile_used")

    return out
