from __future__ import annotations

import asyncio
import logging
from typing import Optional

import numpy as np

from app.config import settings
from app.emitter import FeatureEmitter
from app.fast_path import extract
from app.session import PipelineSession, SessionRegistry
from app.types import (
    ChannelFamily,
    FeatureFrame,
    LatencyMs,
    LinguisticFamily,
    ProsodyFamily,
    SpeakerFamily,
    VoiceFamily,
    WatermarkFamily,
)

logger = logging.getLogger("sentinelvoice.ml.scheduler")


def _family(model, payload: dict):
    return model.model_validate(payload)


def build_feature_frame(session: PipelineSession, window: np.ndarray) -> FeatureFrame:
    state = session.fast_path
    state.emit_seq = session.emit_seq + 1
    state.cumulative_speech_ms = session.cumulative_speech_ms
    extracted = extract(
        window,
        session.ring_buffer.sample_rate,
        session.profile,
        session_state=state,
    )
    # Prefer orchestrator wall time; fall back to measured total.
    fast_ms = float(extracted.get("fastPathMs") or 0.0)
    sr = session.ring_buffer.sample_rate
    window_end_ms = int(session.ring_buffer.total_samples_written * 1000 / sr)
    window_start_ms = max(0, window_end_ms - int(settings.window_seconds * 1000))
    session.emit_seq += 1
    return FeatureFrame(
        sessionId=session.session_id,
        seq=session.emit_seq,
        windowStartMs=window_start_ms,
        windowEndMs=window_end_ms,
        channelProfile=session.profile,
        speechPresent=bool(extracted["speechPresent"]),
        cumulativeSpeechMs=session.cumulative_speech_ms,
        voice=_family(VoiceFamily, extracted["voice"]),
        channel=_family(ChannelFamily, extracted["channel"]),
        prosody=_family(ProsodyFamily, extracted["prosody"]),
        speaker=_family(SpeakerFamily, extracted["speaker"]),
        watermark=_family(WatermarkFamily, extracted["watermark"]),
        linguistic=_family(LinguisticFamily, extracted["linguistic"]),
        # Frozen schema: only fastPath/slowPath — module breakdown is on /diagnostics.
        latencyMs=LatencyMs(fastPath=fast_ms, slowPath=0.0),
    )


class SessionScheduler:
    """Per-session 500 ms ticks: 2.0 s ring window → fast_path → FeatureFrame emit."""

    def __init__(self, registry: SessionRegistry, emitter: FeatureEmitter) -> None:
        self.registry = registry
        self.emitter = emitter
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._lock = asyncio.Lock()

    async def start(self, session_id: str) -> None:
        if not settings.emit_enabled:
            return
        async with self._lock:
            existing = self._tasks.get(session_id)
            if existing is not None and not existing.done():
                return
            self._tasks[session_id] = asyncio.create_task(
                self._run(session_id),
                name=f"feature-scheduler-{session_id}",
            )
            logger.info("scheduler_start session_id=%s", session_id)

    async def stop(self, session_id: str) -> None:
        async with self._lock:
            task = self._tasks.pop(session_id, None)
        if task is None:
            return
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
        logger.info("scheduler_stop session_id=%s", session_id)

    async def stop_all(self) -> None:
        async with self._lock:
            ids = list(self._tasks)
        for session_id in ids:
            await self.stop(session_id)

    async def tick(self, session_id: str) -> Optional[FeatureFrame]:
        session = self.registry.get(session_id)
        if session is None:
            return None
        needed = int(settings.window_seconds * session.ring_buffer.sample_rate)
        if session.ring_buffer.total_samples_written < needed:
            return None
        window = session.ring_buffer.read_window(settings.window_seconds, hop_offset_s=0.0)
        # DSP releases the GIL; keep the event loop free.
        frame = await asyncio.to_thread(build_feature_frame, session, window)
        await self.emitter.emit(frame)
        logger.info(
            "feature_emit session_id=%s seq=%s fastPathMs=%.1f speechPresent=%s",
            session.session_id,
            frame.seq,
            frame.latencyMs.fastPath,
            frame.speechPresent,
        )
        return frame

    async def _run(self, session_id: str) -> None:
        interval = settings.emit_interval_ms / 1000.0
        try:
            while True:
                await self.tick(session_id)
                await asyncio.sleep(interval)
        except asyncio.CancelledError:
            raise
