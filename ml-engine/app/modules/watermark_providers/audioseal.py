"""AudioSeal (Meta) watermark detector — real, released detector (Context §10.7).

Uses ``audioseal.AudioSeal.load_detector`` + ``detect_watermark`` for clip-level
detection and ``forward`` for sample-level localisation spans.
"""

from __future__ import annotations

import logging
import os
from typing import Any, Optional

import numpy as np

from app.modules.watermark_providers.base import WatermarkProvider

logger = logging.getLogger(__name__)

# AudioSeal models are trained at 16 kHz.
_TARGET_SR = 16_000
_DEFAULT_CARD = "audioseal_detector_16bits"
_DETECTION_THRESHOLD = 0.5
_LOCALISATION_THRESHOLD = 0.5
# Merge contiguous above-threshold samples into spans; drop spans shorter than this.
_MIN_SPAN_SAMPLES = 160  # 10 ms @ 16 kHz


def _disable_torch_compile() -> None:
    """AudioSeal's generator/detector path can hit torch.compile; disable on hosts without a C++ toolchain."""
    os.environ.setdefault("TORCH_COMPILE_DISABLE", "1")
    try:
        import torch

        torch._dynamo.config.disable = True  # type: ignore[attr-defined]
    except Exception:  # noqa: BLE001 — best-effort; detection still works without this
        pass


def _to_mono_float32(audio: np.ndarray) -> np.ndarray:
    samples = np.asarray(audio, dtype=np.float32)
    if samples.ndim > 1:
        samples = np.mean(samples, axis=-1 if samples.shape[-1] <= 8 else 0)
    return samples.reshape(-1)


def _resample_linear(samples: np.ndarray, sr: int, target_sr: int) -> np.ndarray:
    if sr == target_sr or samples.size == 0:
        return samples
    duration = samples.size / float(sr)
    n_out = max(1, int(round(duration * target_sr)))
    x_old = np.linspace(0.0, 1.0, num=samples.size, endpoint=False, dtype=np.float64)
    x_new = np.linspace(0.0, 1.0, num=n_out, endpoint=False, dtype=np.float64)
    return np.interp(x_new, x_old, samples.astype(np.float64)).astype(np.float32)


def _spans_from_frame_probs(
    frame_probs: np.ndarray,
    sr: int,
    threshold: float = _LOCALISATION_THRESHOLD,
) -> list[dict[str, float]]:
    """Convert per-sample watermark probabilities into contiguous time spans."""
    if frame_probs.size == 0:
        return []
    above = frame_probs >= threshold
    spans: list[dict[str, float]] = []
    start: Optional[int] = None
    for i, flag in enumerate(above.tolist()):
        if flag and start is None:
            start = i
        elif not flag and start is not None:
            end = i
            if end - start >= _MIN_SPAN_SAMPLES:
                segment = frame_probs[start:end]
                spans.append(
                    {
                        "start_s": start / float(sr),
                        "end_s": end / float(sr),
                        "confidence": float(np.mean(segment)),
                    }
                )
            start = None
    if start is not None:
        end = above.size
        if end - start >= _MIN_SPAN_SAMPLES:
            segment = frame_probs[start:end]
            spans.append(
                {
                    "start_s": start / float(sr),
                    "end_s": end / float(sr),
                    "confidence": float(np.mean(segment)),
                }
            )
    return spans


class AudioSealProvider(WatermarkProvider):
    """Meta AudioSeal released detector."""

    def __init__(
        self,
        model_card: str = _DEFAULT_CARD,
        detection_threshold: float = _DETECTION_THRESHOLD,
        device: str | None = None,
    ) -> None:
        self._model_card = model_card
        self._detection_threshold = float(detection_threshold)
        self._device = device
        self._detector = None

    @property
    def name(self) -> str:
        return "audioseal"

    def _ensure_detector(self):
        if self._detector is not None:
            return self._detector
        _disable_torch_compile()
        import torch
        from audioseal import AudioSeal

        det = AudioSeal.load_detector(self._model_card)
        if self._device:
            det = det.to(self._device)
        det.eval()
        self._detector = det
        self._torch = torch
        return det

    def detect(self, audio: np.ndarray, sr: int) -> dict[str, Any]:
        samples = _to_mono_float32(audio)
        if samples.size == 0 or sr <= 0:
            return {
                "available": False,
                "reason": "empty_audio",
                "detected": False,
                "provider": self.name,
                "confidence": 0.0,
                "spans": [],
            }

        try:
            det = self._ensure_detector()
            torch = self._torch
        except Exception as exc:  # noqa: BLE001
            logger.warning("audioseal_load_failed err=%s", exc)
            return {
                "available": False,
                "reason": f"audioseal_load_failed: {exc}",
                "detected": False,
                "provider": self.name,
                "confidence": 0.0,
                "spans": [],
            }

        samples_16k = _resample_linear(samples, int(sr), _TARGET_SR)
        # Detector expects batch × channels × frames.
        x = torch.from_numpy(samples_16k).view(1, 1, -1)
        if self._device:
            x = x.to(self._device)

        with torch.no_grad():
            detect_prob, _message = det.detect_watermark(
                x,
                sample_rate=_TARGET_SR,
                detection_threshold=self._detection_threshold,
            )
            result_2ch, _ = det(x, sample_rate=_TARGET_SR)

        confidence = float(detect_prob.detach().cpu().reshape(-1)[0].item())
        detected = confidence >= self._detection_threshold

        # result_2ch: B × 2 × T — channel 1 is P(watermarked) after softmax.
        frame_probs = result_2ch[0, 1, :].detach().cpu().numpy().astype(np.float64)
        # Map frame probs back onto the original sample rate timeline for spans.
        if int(sr) != _TARGET_SR and frame_probs.size > 0:
            n_orig = samples.size
            idx = np.linspace(0, frame_probs.size - 1, num=n_orig).astype(np.int64)
            frame_probs_orig = frame_probs[idx]
            spans = _spans_from_frame_probs(frame_probs_orig, int(sr))
        else:
            spans = _spans_from_frame_probs(frame_probs, _TARGET_SR)

        if not detected:
            spans = []

        return {
            "available": True,
            "detected": detected,
            "provider": self.name,
            "confidence": confidence,
            "spans": spans,
            "detector": f"audioseal:{self._model_card}",
        }
