"""WatermarkProvider ABC — pluggable attribution detectors (Context §10.7)."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any

import numpy as np


class WatermarkProvider(ABC):
    """Detect a known generative-audio watermark scheme.

    Implementations return attribution metadata only. Callers must never feed
    the result into the fusion risk score (Context §10.7 / §16.3).
    """

    @property
    @abstractmethod
    def name(self) -> str:
        """Stable provider id (e.g. ``audioseal``, ``synthid``)."""

    @abstractmethod
    def detect(self, audio: np.ndarray, sr: int) -> dict[str, Any]:
        """Scan ``audio`` at sample rate ``sr``.

        Successful detection returns at least::

            {
                "detected": bool,
                "provider": str,
                "confidence": float,   # 0..1
                "spans": list,         # sample-level localisation windows
            }

        Providers that are not runnable locally (e.g. SynthID) return::

            {"available": False, "reason": "<honest explanation>"}
        """
