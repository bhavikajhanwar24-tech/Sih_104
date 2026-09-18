"""Learned anti-spoofing classifier (Context §3, §10.1, §15).

ACTIVE TIER: 1 — LFCC + delta + delta-delta → Tiny LCNN, with Platt-calibrated
spoofProbability. Trains in minutes on CPU; live-path budget < 50 ms / 2 s window.

TIER 2 (not active): wav2vec2 / WavLM front-end or AASIST graph-attention — only
enable if Tier 1 is end-to-end green and Tier 2 stays under the latency budget
(or is quantised to ONNX int8 / used offline only).

spoofProbability is ALWAYS the calibrated probability in [0, 1], never a raw logit —
the fusion engine treats it as a probability.
"""

from __future__ import annotations

import json
import logging
import time
from pathlib import Path
from typing import Any, Optional

import numpy as np
import torch
from numpy.typing import NDArray

from app.types import ChannelProfile

logger = logging.getLogger("sentinelvoice.ml.antispoof")

ACTIVE_TIER = 1
MODEL_ID_DEFAULT = "lfcc-lcnn-tier1/codec_aug"
LATENCY_BUDGET_MS = 50.0

_ROOT = Path(__file__).resolve().parents[2]
_DEFAULT_CKPT = _ROOT / "models" / "antispoof" / "codec_aug.pt"
_DEFAULT_CAL = _ROOT / "models" / "antispoof" / "calibration.json"

_model: Any = None
_calibration: dict[str, dict[str, float]] = {}
_model_id: str = MODEL_ID_DEFAULT
_warmup_ms: Optional[float] = None
_infer_latency_ms: Optional[float] = None
_ready: bool = False


def _profile_key(profile: ChannelProfile | str | None) -> str:
    if profile is None:
        return ChannelProfile.WEBRTC_WIDEBAND.value
    return profile.value if isinstance(profile, ChannelProfile) else str(profile)


def _sigmoid(x: float) -> float:
    if x >= 0:
        z = np.exp(-x)
        return float(1.0 / (1.0 + z))
    z = np.exp(x)
    return float(z / (1.0 + z))


def _platt(logit: float, profile: str) -> float:
    params = _calibration.get(profile) or _calibration.get(ChannelProfile.WEBRTC_WIDEBAND.value)
    if not params:
        # Uncalibrated fallback — still map through sigmoid so fusion sees a probability.
        return _sigmoid(logit)
    a = float(params.get("A", 1.0))
    b = float(params.get("B", 0.0))
    return _sigmoid(a * logit + b)


def _predictive_confidence(p: float) -> float:
    """confidence = 1 - normalised binary entropy."""
    p = float(np.clip(p, 1e-6, 1.0 - 1e-6))
    h = -p * np.log2(p) - (1 - p) * np.log2(1 - p)
    return float(np.clip(1.0 - h, 0.0, 1.0))


def is_ready() -> bool:
    return _ready


def infer_latency_ms() -> Optional[float]:
    return _infer_latency_ms


def warmup(
    checkpoint: Optional[Path] = None,
    calibration: Optional[Path] = None,
    force: bool = False,
) -> dict[str, Any]:
    """Load Tier-1 checkpoint + calibration once; measure CPU latency on a 2 s window."""
    global _model, _calibration, _model_id, _warmup_ms, _infer_latency_ms, _ready

    if _ready and not force:
        return {
            "ready": True,
            "tier": ACTIVE_TIER,
            "model_id": _model_id,
            "warmup_ms": _warmup_ms,
            "infer_latency_ms": _infer_latency_ms,
        }

    ckpt = Path(checkpoint) if checkpoint else _DEFAULT_CKPT
    cal_path = Path(calibration) if calibration else _DEFAULT_CAL

    t0 = time.perf_counter()
    if not ckpt.is_file():
        _ready = False
        logger.warning("antispoof_checkpoint_missing path=%s", ckpt)
        return {"ready": False, "reason": "checkpoint_missing", "path": str(ckpt)}

    from training.features import STACK_DIM, TinyLCNN, lfcc_stack, pad_stack

    blob = torch.load(ckpt, map_location="cpu", weights_only=False)
    model = TinyLCNN(int(blob.get("stack_dim", STACK_DIM)))
    model.load_state_dict(blob["state_dict"])
    model.eval()
    _model = model
    _model_id = str(blob.get("model_id", MODEL_ID_DEFAULT))

    _calibration = {}
    if cal_path.is_file():
        cal = json.loads(cal_path.read_text(encoding="utf-8"))
        for prof, params in (cal.get("profiles") or {}).items():
            _calibration[prof] = {"A": float(params["A"]), "B": float(params["B"])}
    else:
        logger.warning("antispoof_calibration_missing path=%s — using sigmoid(logit)", cal_path)

    _warmup_ms = (time.perf_counter() - t0) * 1000.0

    probe = (0.05 * np.random.default_rng(0).standard_normal(16000 * 2)).astype(np.float32)
    t1 = time.perf_counter()
    _ = score(probe, 16000, ChannelProfile.WEBRTC_WIDEBAND)
    _infer_latency_ms = (time.perf_counter() - t1) * 1000.0
    _ready = True

    logger.info(
        "antispoof_loaded tier=%s model_id=%s warmup_ms=%.1f infer_latency_ms=%.1f budget_ms=%.1f",
        ACTIVE_TIER,
        _model_id,
        _warmup_ms,
        _infer_latency_ms,
        LATENCY_BUDGET_MS,
    )
    return {
        "ready": True,
        "tier": ACTIVE_TIER,
        "model_id": _model_id,
        "warmup_ms": _warmup_ms,
        "infer_latency_ms": _infer_latency_ms,
        "within_budget": bool(_infer_latency_ms is not None and _infer_latency_ms < LATENCY_BUDGET_MS),
    }


@torch.inference_mode()
def score(
    audio: NDArray[np.floating],
    sr: int,
    profile: ChannelProfile | str | None = None,
) -> dict[str, Any]:
    """Return calibrated spoofProbability for one window."""
    if _model is None:
        return {
            "available": False,
            "spoofProbability": None,
            "modelId": None,
            "confidence": None,
            "reason": "model_not_loaded",
        }

    samples = np.asarray(audio, dtype=np.float32).reshape(-1)
    if samples.size < sr // 4:
        return {
            "available": False,
            "spoofProbability": None,
            "modelId": _model_id,
            "confidence": None,
            "reason": "audio_too_short",
        }

    from training.features import lfcc_stack, pad_stack

    max_frames = 200
    stack = pad_stack(lfcc_stack(samples, sr, max_frames=max_frames), max_frames)
    x = torch.from_numpy(stack).unsqueeze(0).unsqueeze(0)
    logit = float(_model(x).item())
    key = _profile_key(profile)
    prob = float(np.clip(_platt(logit, key), 0.0, 1.0))
    conf = _predictive_confidence(prob)
    return {
        "available": True,
        "spoofProbability": prob,
        "modelId": _model_id,
        "confidence": conf,
        "rawLogit": logit,  # debug only — fusion must use spoofProbability
        "tier": ACTIVE_TIER,
        "channelProfile": key,
    }


def extract(
    audio: np.ndarray,
    sr: int,
    profile: ChannelProfile | str | None = None,
) -> dict[str, Any]:
    """Feature-module shape used by the fast path."""
    return score(audio, sr, profile)
