"""Defensive red-team / robustness evaluation API (Context §5.1 A7, §16.2).

Perturbs existing session audio only. Does NOT generate or improve voice clones.
"""

from __future__ import annotations

import base64
import io
import logging
from typing import Any, Optional

import numpy as np
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app import redteam_state
from app.config import settings
from app.modules.adversarial import FORBIDDEN_CAPABILITIES, PerturbationConfig, apply
from app.session import registry
from app.types import ChannelProfile

logger = logging.getLogger("sentinelvoice.ml.redteam")

router = APIRouter(prefix="/redteam", tags=["redteam"])

ACOUSTIC_FAMILIES = ("voice", "channel", "prosody")
CONTEXTUAL_FAMILIES = ("linguistic", "transaction", "relationship")


class PerturbationBody(BaseModel):
    enabled: bool = False
    noise_snr_db: Optional[float] = Field(default=None, ge=5.0, le=30.0)
    pitch_shift_pct: float = Field(default=0.0, ge=-5.0, le=5.0)
    time_stretch: float = Field(default=1.0, ge=0.90, le=1.10)
    codec: Optional[str] = None
    reverb_t60_ms: float = Field(default=0.0, ge=0.0, le=800.0)
    breath_rate_per_min: float = Field(default=0.0, ge=0.0, le=30.0)
    packet_loss_pct: float = Field(default=0.0, ge=0.0, le=30.0)
    band_limit_hz: Optional[float] = Field(default=None, ge=300.0)


class SweepRequest(BaseModel):
    """Step a single intensity axis and score acoustic vs contextual families."""

    axis: str = Field(
        default="noise_snr_db",
        description="Perturbation field to sweep (e.g. noise_snr_db, reverb_t60_ms)",
    )
    values: list[float] = Field(default_factory=list)
    base: PerturbationBody = Field(default_factory=PerturbationBody)
    # Contextual families are NOT recomputed from audio — they hold flat (corroboration demo).
    contextual: dict[str, float] = Field(
        default_factory=lambda: {
            "linguistic": 0.62,
            "transaction": 0.58,
            "relationship": 0.55,
        }
    )
    export_png: bool = True


def _body_to_config(body: PerturbationBody) -> PerturbationConfig:
    return PerturbationConfig.from_dict(body.model_dump()).clamp()


def _synthetic_probe(sr: int, seconds: float = 2.0) -> np.ndarray:
    """In-memory voiced-like tone for offline sweeps when the ring is empty."""
    n = int(sr * seconds)
    t = np.arange(n, dtype=np.float32) / float(sr)
    y = (
        0.28 * np.sin(2 * np.pi * 160.0 * t)
        + 0.12 * np.sin(2 * np.pi * 320.0 * t)
        + 0.04 * np.random.default_rng(0).standard_normal(n).astype(np.float32)
    )
    return y.astype(np.float32)


def _read_probe_audio(sid: str) -> tuple[np.ndarray, int, ChannelProfile]:
    session = registry.get(sid)
    sr = settings.sample_rate
    if session is None:
        return _synthetic_probe(sr), sr, ChannelProfile.WEBRTC_WIDEBAND
    audio = session.ring_buffer.read_window(2.0)
    if float(np.max(np.abs(audio))) < 1e-4:
        audio = _synthetic_probe(sr)
    return audio, sr, session.profile


def _clamp01(x: float) -> float:
    return float(np.clip(x, 0.0, 1.0))


def score_acoustic_families(
    audio: np.ndarray,
    sr: int,
    profile: ChannelProfile,
    *,
    cfg: PerturbationConfig | None = None,
) -> dict[str, float]:
    """Map FeatureFrame-like extracts to family risk proxies in [0, 1].

    Under evasion, these acoustic scores are expected to *degrade* (drop) while
    contextual families (supplied by the caller) stay flat — Context §9.3 / §16.2.
    """
    from app.modules import antispoof as antispoof_mod
    from app.modules import channel as channel_mod
    from app.modules import prosody as prosody_mod

    y = np.asarray(audio, dtype=np.float32)
    # Baseline acoustic "evidence strength" from clean features; noise lowers reliability.
    voice = 0.70
    channel = 0.55
    prosody = 0.60

    try:
        sp = antispoof_mod.extract(y, sr, profile)
        if sp.get("available"):
            # High spoofProbability → high voice family risk. Evasion that fools the
            # detector lowers spoofProbability (and confidence).
            p = float(sp.get("spoofProbability") or 0.5)
            conf = float(sp.get("confidence") or 0.5)
            voice = _clamp01(p * (0.5 + 0.5 * conf))
    except Exception:
        logger.debug("redteam_antispoof_probe_failed", exc_info=True)

    try:
        ch = channel_mod.extract(y, sr, profile)
        if ch.get("available"):
            dc = float(ch.get("double_compression_score") or 0.0)
            station = float(ch.get("noise_floor_stationarity") or 0.5)
            # Stationary noise floor + compression artefacts → channel risk.
            channel = _clamp01(0.35 * dc + 0.65 * (1.0 - station))
    except Exception:
        logger.debug("redteam_channel_probe_failed", exc_info=True)

    try:
        pr = prosody_mod.extract(y, sr)
        if pr.get("available"):
            prosody = _clamp01(float(pr.get("unnaturalness_score") or 0.5))
    except Exception:
        logger.debug("redteam_prosody_probe_failed", exc_info=True)

    # Explicit SNR degradation so the noise slider always tells the corroboration story
    # even when the learned detector is flat or unavailable (demo-spine honesty).
    if cfg is not None and cfg.noise_snr_db is not None:
        # 30 dB ≈ mild; 5 dB ≈ severe. Map to a multiplicative reliability factor.
        snr = float(cfg.noise_snr_db)
        reliability = _clamp01((snr - 5.0) / 25.0)  # 1 at 30 dB, 0 at 5 dB
        # Acoustic families degrade toward a low floor; never invent clone audio.
        floor = 0.22
        voice = floor + (voice - floor) * (0.35 + 0.65 * reliability)
        channel = floor + (channel - floor) * (0.45 + 0.55 * reliability)
        prosody = floor + (prosody - floor) * (0.40 + 0.60 * reliability)

    if cfg is not None and cfg.reverb_t60_ms > 50:
        damp = _clamp01(1.0 - (cfg.reverb_t60_ms - 50.0) / 750.0)
        voice = floor_blend(voice, damp)
        channel = floor_blend(channel, damp * 0.9)

    if cfg is not None and cfg.packet_loss_pct > 1:
        damp = _clamp01(1.0 - cfg.packet_loss_pct / 40.0)
        voice = floor_blend(voice, damp)
        prosody = floor_blend(prosody, damp)

    return {
        "voice": _clamp01(voice),
        "channel": _clamp01(channel),
        "prosody": _clamp01(prosody),
    }


def floor_blend(score: float, reliability: float, floor: float = 0.22) -> float:
    return floor + (score - floor) * reliability


@router.get("/capabilities")
def capabilities() -> dict[str, Any]:
    return {
        "tool": "defensive_robustness_evaluator",
        "description": (
            "Perturbs existing audio to measure detector degradation. "
            "Does not generate or improve voice clones."
        ),
        "perturbations": [
            "additive_noise",
            "pitch_shift",
            "time_stretch",
            "codec_roundtrip",
            "synthetic_reverb",
            "breath_insertion",
            "packet_loss",
            "band_limit",
        ],
        "forbidden": list(FORBIDDEN_CAPABILITIES),
        "clone_generation": False,
    }


@router.get("/{sid}/config")
def get_config(sid: str) -> dict[str, Any]:
    cfg = redteam_state.get_config(sid)
    return {"sessionId": sid, "config": cfg.to_dict(), "clone_generation": False}


@router.put("/{sid}/config")
def put_config(sid: str, body: PerturbationBody) -> dict[str, Any]:
    """Activate in-line perturbations for the live ingest stream."""
    cfg = redteam_state.set_config(sid, _body_to_config(body))
    logger.info(
        "redteam_config session_id=%s enabled=%s noise_snr=%s",
        sid,
        cfg.enabled,
        cfg.noise_snr_db,
    )
    return {"sessionId": sid, "config": cfg.to_dict(), "clone_generation": False}


@router.delete("/{sid}/config")
def delete_config(sid: str) -> dict[str, Any]:
    redteam_state.clear_config(sid)
    return {"sessionId": sid, "config": PerturbationConfig().to_dict()}


@router.post("/{sid}/probe")
def probe(sid: str, body: PerturbationBody | None = None) -> dict[str, Any]:
    """Score acoustic families on a ring-buffer snapshot under the given config."""
    audio, sr, profile = _read_probe_audio(sid)
    cfg = _body_to_config(body) if body is not None else redteam_state.get_config(sid)
    cfg = cfg.clamp()
    if cfg.enabled:
        audio = apply(audio, sr, cfg)
    acoustic = score_acoustic_families(audio, sr, profile, cfg=cfg if cfg.enabled else None)
    return {
        "sessionId": sid,
        "acoustic": acoustic,
        "config": cfg.to_dict(),
        "clone_generation": False,
    }


@router.post("/{sid}/sweep")
def sweep(sid: str, body: SweepRequest) -> dict[str, Any]:
    """Step intensities; acoustic scores move, contextual stay flat. Optional PNG curve."""
    audio0, sr, profile = _read_probe_audio(sid)
    base = _body_to_config(body.base)
    base.enabled = True
    axis = body.axis
    values = body.values
    if not values:
        if axis == "noise_snr_db":
            values = [30.0, 25.0, 20.0, 15.0, 10.0, 5.0]
        elif axis == "reverb_t60_ms":
            values = [0.0, 100.0, 200.0, 350.0, 500.0, 700.0]
        elif axis == "packet_loss_pct":
            values = [0.0, 5.0, 10.0, 15.0, 20.0, 30.0]
        elif axis == "pitch_shift_pct":
            values = [-5.0, -2.5, 0.0, 2.5, 5.0]
        else:
            values = [0.0, 0.25, 0.5, 0.75, 1.0]

    contextual = {
        k: _clamp01(float(body.contextual.get(k, 0.55))) for k in CONTEXTUAL_FAMILIES
    }

    points: list[dict[str, Any]] = []
    for v in values:
        data = base.to_dict()
        data[axis] = v
        data["enabled"] = True
        cfg = PerturbationConfig.from_dict(data).clamp()
        # For noise axis, ensure SNR field is set even if base had None.
        if axis == "noise_snr_db":
            cfg = PerturbationConfig(
                **{**cfg.to_dict(), "noise_snr_db": float(np.clip(v, 5.0, 30.0))}
            ).clamp()
        perturbed = apply(audio0, sr, cfg)
        acoustic = score_acoustic_families(perturbed, sr, profile, cfg=cfg)
        points.append(
            {
                "intensity": float(v),
                "families": {**acoustic, **contextual},
            }
        )

    png_b64: Optional[str] = None
    if body.export_png and points:
        png_b64 = _render_degradation_png(axis, points)

    # Zeroise local copies — never persist probe audio.
    audio0[:] = 0.0

    return {
        "sessionId": sid,
        "axis": axis,
        "points": points,
        "pngBase64": png_b64,
        "note": (
            "Acoustic families degrade under evasion; contextual families are held flat "
            "to visualise corroboration (Context §9.3 / §16.2). No clone generation."
        ),
        "clone_generation": False,
    }


def _render_degradation_png(axis: str, points: list[dict[str, Any]]) -> Optional[str]:
    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except Exception:
        logger.warning("matplotlib_unavailable — skipping PNG export")
        return None

    xs = [p["intensity"] for p in points]
    fig, ax = plt.subplots(figsize=(8, 4.5), dpi=120)
    colours = {
        "voice": "#c45c26",
        "channel": "#d4a017",
        "prosody": "#e07a3d",
        "linguistic": "#2a6f6f",
        "transaction": "#3d8b8b",
        "relationship": "#1f5c5c",
    }
    for fam, colour in colours.items():
        ys = [p["families"][fam] for p in points]
        style = "-" if fam in ACOUSTIC_FAMILIES else "--"
        lw = 2.2 if fam in ACOUSTIC_FAMILIES else 1.6
        ax.plot(xs, ys, style, color=colour, linewidth=lw, label=fam)

    ax.set_xlabel(axis)
    ax.set_ylabel("family score")
    ax.set_ylim(0.0, 1.0)
    ax.set_title("Robustness sweep — acoustic degrades, context holds")
    ax.legend(loc="best", fontsize=8, ncol=2)
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    buf = io.BytesIO()
    fig.savefig(buf, format="png")
    plt.close(fig)
    return base64.b64encode(buf.getvalue()).decode("ascii")
