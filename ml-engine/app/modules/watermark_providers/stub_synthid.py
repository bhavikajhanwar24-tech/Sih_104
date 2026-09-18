"""Honest SynthID stub — detection is NOT publicly available (Context §16.3).

Judge Q&A (§16.3):
  "No. AudioSeal, yes — it's open-source with a published detector and we run it.
   SynthID detection is only available through Google's own tooling for their
   content; we've built the scanner as a pluggable interface so a provider
   detector drops in. We also don't let watermark results move the risk score
   at all, because no real attacker uses watermarked TTS — absence of a
   watermark must never look like evidence of authenticity."

This module intentionally does **not** fake a SynthID detector. When a
provider-side API becomes available, implement a real ``WatermarkProvider``
and register it in ``app.modules.watermark`` — do not invent scores here.
"""

from __future__ import annotations

from typing import Any

import numpy as np

from app.modules.watermark_providers.base import WatermarkProvider

# Exact unavailable reason surfaced to callers / judges (Context §16.3 wording).
UNAVAILABLE_REASON = "SynthID detection requires provider-side tooling"


class SynthIdStubProvider(WatermarkProvider):
    """Placeholder that reports SynthID detection as unavailable."""

    @property
    def name(self) -> str:
        return "synthid"

    def detect(self, audio: np.ndarray, sr: int) -> dict[str, Any]:
        # audio/sr unused — there is no local detector to run.
        _ = (audio, sr)
        return {
            "available": False,
            "reason": UNAVAILABLE_REASON,
            "detected": False,
            "provider": self.name,
            "confidence": 0.0,
            "spans": [],
        }
