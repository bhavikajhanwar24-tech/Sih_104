"""Watermark attribution scanner (Context §10.6 / P13.3).

Pluggable provider interface. Until AudioSeal (or a provider API) is wired, the
module reports ``available=False`` — watermark absence must NEVER feed the risk
score (no attacker uses a watermarking TTS on purpose).
"""

from __future__ import annotations

from typing import Any, Optional

import numpy as np

from app.types import ChannelProfile


def extract(
    audio: np.ndarray,
    sr: int,
    profile: ChannelProfile | str | None = None,
) -> dict[str, Any]:
    """Scan for known generative-audio watermarks.

    Returns available=False when no provider is configured. Callers must treat
    this as attribution-only enrichment, never as a fusion input.
    """
    samples = np.asarray(audio, dtype=np.float32).reshape(-1)
    if samples.size == 0 or sr <= 0:
        return {"available": False, "reason": "empty_audio"}
    # Provider hook (AudioSeal / SynthID) lands in P13.3.
    return {
        "available": False,
        "reason": "watermark_provider_not_configured",
        "detector": None,
        "detected": None,
        "provider": None,
        "confidence": None,
    }
