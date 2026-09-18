"""Watermark attribution scanner (Context §10.7 / P13.3).

Runs pluggable providers (AudioSeal real; SynthID honest stub). Results are
**attribution enrichment only** — they must never feed the fusion risk score.

CRITICAL SEMANTICS (code-level guarantee, not a convention):
  No real attacker uses a watermarking TTS, so absence of a watermark carries
  zero information. Wiring watermark detection into the score would make
  unwatermarked audio look safer than it is. See ``ATTRIBUTION_ONLY`` and
  ``tests/test_watermark.py::test_architecture_watermark_cannot_influence_risk``.
"""

from __future__ import annotations

import logging
from typing import Any, Optional

import numpy as np

from app.modules.watermark_providers.audioseal import AudioSealProvider
from app.modules.watermark_providers.base import WatermarkProvider
from app.modules.watermark_providers.stub_synthid import SynthIdStubProvider
from app.types import ChannelProfile

logger = logging.getLogger(__name__)

# Architecture invariant: watermark output is never a fusion family input.
# Fusion weights (application.yml) and EvidenceFamily enum omit "watermark";
# FusionEngineService.extractFamilies never reads frame.watermark().
# The architecture test in test_watermark.py asserts this cannot regress.
ATTRIBUTION_ONLY: bool = True

# Reason code emitted by the Decision Plane when detected=True (INFO severity).
WATERMARK_DETECTED_REASON = "WATERMARK_DETECTED"

_default_providers: list[WatermarkProvider] | None = None


def _providers() -> list[WatermarkProvider]:
    global _default_providers
    if _default_providers is None:
        _default_providers = [AudioSealProvider(), SynthIdStubProvider()]
    return _default_providers


def extract(
    audio: np.ndarray,
    sr: int,
    profile: ChannelProfile | str | None = None,
    providers: Optional[list[WatermarkProvider]] = None,
) -> dict[str, Any]:
    """Scan for known generative-audio watermarks (attribution only).

    Returns a FeatureFrame-compatible watermark block. Spans from the winning
    provider are retained for localisation/tests but stripped by
    ``fast_path._map_watermark`` before schema emission (schema has no spans).

    Asserts ``ATTRIBUTION_ONLY`` so any caller that treats this as a score
    input fails loudly if the invariant is flipped.
    """
    assert ATTRIBUTION_ONLY, (
        "watermark.ATTRIBUTION_ONLY must remain True — watermark results must "
        "never feed the fusion risk score (Context §10.7)."
    )
    _ = profile  # channel profile does not change attribution semantics

    samples = np.asarray(audio, dtype=np.float32).reshape(-1)
    if samples.size == 0 or sr <= 0:
        return {
            "available": False,
            "reason": "empty_audio",
            "detector": None,
            "detected": False,
            "provider": None,
            "confidence": 0.0,
            "spans": [],
            "attribution_only": True,
        }

    chain = providers if providers is not None else _providers()
    best: dict[str, Any] | None = None
    unavailable_notes: list[str] = []

    for provider in chain:
        try:
            result = provider.detect(samples, int(sr))
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "watermark_provider_failed provider=%s err=%s",
                getattr(provider, "name", type(provider).__name__),
                exc,
            )
            unavailable_notes.append(f"{getattr(provider, 'name', '?')}:error")
            continue

        if result.get("available") is False:
            reason = str(result.get("reason") or "unavailable")
            unavailable_notes.append(f"{result.get('provider') or provider.name}:{reason}")
            continue

        # Prefer a positive detection; otherwise keep the first available scan.
        if bool(result.get("detected")):
            best = result
            break
        if best is None:
            best = result

    if best is None:
        return {
            "available": False,
            "reason": "; ".join(unavailable_notes) or "no_provider_available",
            "detector": None,
            "detected": False,
            "provider": None,
            "confidence": 0.0,
            "spans": [],
            "attribution_only": True,
        }

    detected = bool(best.get("detected"))
    provider_name = str(best.get("provider") or "unknown")
    confidence = float(best.get("confidence") or 0.0)

    if detected:
        # Decision Plane emits WATERMARK_DETECTED (INFO) from this block;
        # identity card surfaces it as an attribution note — never a risk bump.
        logger.info(
            "WATERMARK_DETECTED provider=%s confidence=%.3f attribution_only=true",
            provider_name,
            confidence,
        )

    return {
        "available": True,
        "detector": str(best.get("detector") or provider_name),
        "detected": detected,
        "provider": provider_name,
        "confidence": confidence,
        "spans": list(best.get("spans") or []),
        "attribution_only": True,
        "reason_code": WATERMARK_DETECTED_REASON if detected else None,
    }
