"""Fast-path feature orchestration (Context §8.1).

Runs spectral, phase, prosody, breath, channel, speaker, antispoof, and watermark
under a 120 ms budget with per-module isolation: one failure never kills the call.

NOTE on latencyMs: the frozen FeatureFrame schema only allows ``fastPath`` / ``slowPath``.
Per-module timing lives on session diagnostics (``GET /diagnostics/{sessionId}``), not as
invented FeatureFrame fields.
"""

from __future__ import annotations

import logging
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

import numpy as np

from app.types import ChannelProfile
from app.vad import is_speech

logger = logging.getLogger("sentinelvoice.ml.fast_path")

FAST_PATH_BUDGET_MS = 120.0
OVER_BUDGET_STREAK = 5
_MODULE_NAMES = (
    "spectral",
    "phase",
    "prosody",
    "breath",
    "channel",
    "speaker",
    "antispoof",
    "watermark",
)

_executor = ThreadPoolExecutor(max_workers=6, thread_name_prefix="fast-path")


@dataclass
class ModuleDiag:
    available: Optional[bool] = None
    last_error: Optional[str] = None
    latencies_ms: list[float] = field(default_factory=list)

    def record(self, ms: float, available: bool, error: Optional[str] = None) -> None:
        self.latencies_ms.append(float(ms))
        if len(self.latencies_ms) > 128:
            del self.latencies_ms[:-64]
        self.available = available
        if error is not None:
            self.last_error = error


@dataclass
class FastPathState:
    """Per-session orchestration state (degradation + diagnostics)."""

    session_id: str
    skip_cqt: bool = False
    speaker_every_n: int = 1
    speaker_tick: int = 0
    over_budget_streak: int = 0
    logged_errors: set[str] = field(default_factory=set)
    modules: dict[str, ModuleDiag] = field(
        default_factory=lambda: {n: ModuleDiag() for n in _MODULE_NAMES}
    )
    total_latencies_ms: list[float] = field(default_factory=list)
    last_breakdown_ms: dict[str, float] = field(default_factory=dict)
    enrolled_embeddings: Optional[dict[str, np.ndarray]] = None
    enrolled_profile_id: Optional[str] = None
    emit_seq: int = 0
    cumulative_speech_ms: int = 0

    def diagnostics(self) -> dict[str, Any]:
        def _pct(vals: list[float], q: float) -> Optional[float]:
            if not vals:
                return None
            return float(np.percentile(np.asarray(vals, dtype=np.float64), q))

        return {
            "sessionId": self.session_id,
            "skipCqt": self.skip_cqt,
            "speakerEveryN": self.speaker_every_n,
            "overBudgetStreak": self.over_budget_streak,
            "budgetMs": FAST_PATH_BUDGET_MS,
            "fastPath": {
                "p50Ms": _pct(self.total_latencies_ms, 50),
                "p95Ms": _pct(self.total_latencies_ms, 95),
                "lastMs": self.total_latencies_ms[-1] if self.total_latencies_ms else None,
                "n": len(self.total_latencies_ms),
            },
            "lastBreakdownMs": dict(self.last_breakdown_ms),
            "modules": {
                name: {
                    "available": diag.available,
                    "lastError": diag.last_error,
                    "p50Ms": _pct(diag.latencies_ms, 50),
                    "p95Ms": _pct(diag.latencies_ms, 95),
                    "lastMs": diag.latencies_ms[-1] if diag.latencies_ms else None,
                }
                for name, diag in self.modules.items()
            },
        }


def _safe_call(
    name: str,
    fn: Callable[[], dict[str, Any]],
    state: FastPathState,
) -> tuple[str, dict[str, Any], float]:
    t0 = time.perf_counter()
    try:
        result = fn()
        if not isinstance(result, dict):
            result = {"available": False, "reason": "invalid_return"}
    except Exception as exc:
        result = {"available": False, "reason": type(exc).__name__}
        key = f"{name}:{type(exc).__name__}"
        if key not in state.logged_errors:
            state.logged_errors.add(key)
            logger.exception(
                "fast_path_module_failed session_id=%s module=%s error=%s",
                state.session_id,
                name,
                type(exc).__name__,
            )
    ms = (time.perf_counter() - t0) * 1000.0
    avail = bool(result.get("available", False))
    err = result.get("reason") if not avail else None
    state.modules[name].record(ms, avail, str(err) if err else None)
    return name, result, ms


def _map_voice(antispoof: dict[str, Any]) -> dict[str, Any]:
    if not antispoof.get("available"):
        return {"available": False}
    return {
        "available": True,
        "spoofProbability": float(antispoof["spoofProbability"]),
        "modelId": str(antispoof.get("modelId") or "unknown"),
        "confidence": float(antispoof.get("confidence") or 0.0),
    }


def _map_channel(ch: dict[str, Any]) -> dict[str, Any]:
    if not ch.get("available"):
        return {"available": False}
    t60 = ch.get("t60_ms")
    if t60 is None:
        return {"available": False}
    return {
        "available": True,
        "rirT60Ms": float(t60),
        "rirPlausible": bool(ch.get("rir_plausible", False)),
        "doubleCompressionScore": float(np.clip(ch.get("double_compression_score", 0.0), 0.0, 1.0)),
        "noiseFloorStationarity": float(np.clip(ch.get("noise_floor_stationarity", 0.0), 0.0, 1.0)),
        "dcOffset": float(ch.get("dc_offset", 0.0)),
    }


def _map_prosody(prosody: dict[str, Any], breath: dict[str, Any]) -> dict[str, Any]:
    if not prosody.get("available"):
        return {"available": False}
    required = [
        prosody.get("f0_mean"),
        prosody.get("f0_std"),
        prosody.get("jitter_local"),
        prosody.get("shimmer_local"),
        prosody.get("hnr_db"),
        prosody.get("articulation_rate_syl_sec"),
        prosody.get("unnaturalness_score"),
    ]
    if any(v is None for v in required):
        return {"available": False}
    breath_rate = breath.get("breath_events_per_min")
    if breath_rate is None:
        breath_rate = 0.0
    # disfluency proxy: pause count normalised by window length estimate
    pause_count = float(prosody.get("pause_count") or 0.0)
    disfluency = float(np.clip(pause_count / 10.0, 0.0, 1.0))
    return {
        "available": True,
        "f0MeanHz": float(prosody["f0_mean"]),
        "f0StdHz": float(prosody["f0_std"]),
        "jitterLocalPct": float(prosody["jitter_local"]) * 100.0,
        "shimmerLocalPct": float(prosody["shimmer_local"]) * 100.0,
        "hnrDb": float(prosody["hnr_db"]),
        "breathEventsPerMin": float(breath_rate),
        "disfluencyRate": disfluency,
        "articulationRateSylSec": float(prosody["articulation_rate_syl_sec"]),
        "unnaturalnessScore": float(np.clip(prosody["unnaturalness_score"], 0.0, 1.0)),
    }


def _map_speaker(spk: dict[str, Any], state: FastPathState) -> dict[str, Any]:
    if not spk.get("available"):
        return {"available": False}
    emb = spk.get("embedding")
    if emb is None:
        return {"available": False}
    drift = float(np.clip(spk.get("intra_call_drift") or 0.0, 0.0, 1.0))
    cosine = spk.get("cosine_similarity")
    if cosine is None:
        # No passport compare — self-consistent embedding present.
        cosine = 1.0
    cosine = float(np.clip(cosine, 0.0, 1.0))
    emb_id = f"emb-{state.session_id}-{state.emit_seq}"
    return {
        "available": True,
        "embeddingId": emb_id,
        "enrolledProfileId": state.enrolled_profile_id,
        "cosineSimilarity": cosine,
        "intraCallDrift": drift,
    }


def _map_watermark(wm: dict[str, Any]) -> dict[str, Any]:
    if not wm.get("available"):
        return {"available": False}
    return {
        "available": True,
        "detector": str(wm.get("detector") or "unknown"),
        "detected": bool(wm.get("detected", False)),
        "provider": wm.get("provider"),
        "confidence": float(np.clip(wm.get("confidence") or 0.0, 0.0, 1.0)),
    }


def _apply_degradation(state: FastPathState, total_ms: float) -> None:
    if total_ms > FAST_PATH_BUDGET_MS:
        state.over_budget_streak += 1
    else:
        state.over_budget_streak = 0
        return
    if state.over_budget_streak < OVER_BUDGET_STREAK:
        return
    if not state.skip_cqt:
        state.skip_cqt = True
        logger.warning(
            "fast_path_degrade session_id=%s action=skip_cqt streak=%d last_ms=%.1f",
            state.session_id,
            state.over_budget_streak,
            total_ms,
        )
        state.over_budget_streak = 0
        return
    if state.speaker_every_n < 4:
        state.speaker_every_n = 4 if state.speaker_every_n < 2 else state.speaker_every_n * 2
        logger.warning(
            "fast_path_degrade session_id=%s action=speaker_every_%d streak=%d last_ms=%.1f",
            state.session_id,
            state.speaker_every_n,
            state.over_budget_streak,
            total_ms,
        )
        state.over_budget_streak = 0


def extract(
    window: np.ndarray,
    sr: int,
    profile: ChannelProfile,
    session_state: Optional[FastPathState] = None,
) -> dict[str, Any]:
    """Orchestrate all fast-path modules into FeatureFrame family blocks."""
    samples = np.asarray(window, dtype=np.float32).reshape(-1)
    state = session_state or FastPathState(session_id="_anon")
    t_all = time.perf_counter()

    from app.modules import antispoof as antispoof_mod
    from app.modules import breath as breath_mod
    from app.modules import channel as channel_mod
    from app.modules import phase as phase_mod
    from app.modules import prosody as prosody_mod
    from app.modules import spectral as spectral_mod
    from app.modules import speaker as speaker_mod
    from app.modules import watermark as watermark_mod

    skip_speaker = False
    state.speaker_tick += 1
    if state.speaker_every_n > 1 and (state.speaker_tick % state.speaker_every_n) != 0:
        skip_speaker = True

    jobs: dict[str, Callable[[], dict[str, Any]]] = {
        "spectral": lambda: spectral_mod.extract(
            samples, sr, profile, skip_cqt=state.skip_cqt
        ),
        "phase": lambda: phase_mod.extract(samples, sr),
        "prosody": lambda: prosody_mod.extract(samples, sr),
        "breath": lambda: breath_mod.detect_breaths(
            samples,
            sr,
            cumulative_speech_s=state.cumulative_speech_ms / 1000.0,
        ),
        "channel": lambda: channel_mod.extract(
            samples, sr, profile, session_id=state.session_id
        ),
        "antispoof": lambda: antispoof_mod.extract(samples, sr, profile),
        "watermark": lambda: watermark_mod.extract(samples, sr, profile),
    }
    if not skip_speaker:
        jobs["speaker"] = lambda: speaker_mod.extract(
            samples,
            sr,
            profile,
            session_id=state.session_id,
            enrolled_embeddings=state.enrolled_embeddings,
            enrolled_profile_id=state.enrolled_profile_id,
        )

    results: dict[str, dict[str, Any]] = {}
    breakdown: dict[str, float] = {}
    futures = {_executor.submit(_safe_call, name, fn, state): name for name, fn in jobs.items()}
    for fut in as_completed(futures):
        name, result, ms = fut.result()
        results[name] = result
        breakdown[name] = ms

    if skip_speaker:
        # Reuse prior speaker family availability if any; else mark deferred.
        results["speaker"] = {"available": False, "reason": "deferred_every_n"}
        breakdown["speaker"] = 0.0
        state.modules["speaker"].record(0.0, False, "deferred_every_n")

    total_ms = (time.perf_counter() - t_all) * 1000.0
    breakdown["total"] = total_ms
    state.last_breakdown_ms = breakdown
    state.total_latencies_ms.append(total_ms)
    if len(state.total_latencies_ms) > 256:
        del state.total_latencies_ms[:-128]
    _apply_degradation(state, total_ms)

    # spectral / phase are diagnostic / slow-path enrichment — not FeatureFrame families.
    _ = results.get("spectral"), results.get("phase")

    return {
        "speechPresent": bool(is_speech(samples)),
        "voice": _map_voice(results.get("antispoof") or {"available": False}),
        "channel": _map_channel(results.get("channel") or {"available": False}),
        "prosody": _map_prosody(
            results.get("prosody") or {"available": False},
            results.get("breath") or {},
        ),
        "speaker": _map_speaker(results.get("speaker") or {"available": False}, state),
        "watermark": _map_watermark(results.get("watermark") or {"available": False}),
        "linguistic": {"available": False},
        "profile": profile,
        "latencyBreakdownMs": breakdown,
        "fastPathMs": total_ms,
    }
