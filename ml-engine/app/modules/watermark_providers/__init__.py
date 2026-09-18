"""Pluggable generative-audio watermark detectors (Context §10.7 / P13.3).

Providers are attribution scanners only. Results must never enter the fusion
risk score — see ``app.modules.watermark.ATTRIBUTION_ONLY``.
"""

from __future__ import annotations

from app.modules.watermark_providers.audioseal import AudioSealProvider
from app.modules.watermark_providers.base import WatermarkProvider
from app.modules.watermark_providers.stub_synthid import SynthIdStubProvider

__all__ = [
    "AudioSealProvider",
    "SynthIdStubProvider",
    "WatermarkProvider",
]
