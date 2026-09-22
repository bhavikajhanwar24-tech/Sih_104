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
    # Slow-path linguistic is published asynchronously; never block here.
    linguistic_payload = dict(
        session.slow_path_linguistic or extracted.get("linguistic") or {"available": False}
    )
    # Always fold the latest Stage B judgment onto the wire frame.
    try:
        from app.modules.stage_b import stage_b_runner

        overlay = stage_b_runner.latest(session.session_id)
        if overlay:
            mk = list(linguistic_payload.get("matchedKeywords") or [])
            for t in overlay.get("matchedKeywords") or []:
                if t not in mk:
                    mk.append(t)
            mr = list(linguistic_payload.get("matchedRuleIds") or [])
            for rid in overlay.get("matchedRuleIds") or []:
                if rid not in mr:
                    mr.append(rid)
            linguistic_payload.update(overlay)
            linguistic_payload["matchedKeywords"] = mk[:16]
            linguistic_payload["matchedRuleIds"] = mr[:16]
            linguistic_payload["available"] = True
            if overlay.get("llmThinking"):
                linguistic_payload["llmThinking"] = overlay["llmThinking"]
            elif stage_b_runner.pending(session.session_id):
                linguistic_payload["llmThinking"] = linguistic_payload.get("llmThinking") or (
                    "LLM judging ACTIVE rules against the latest transcript…"
                )
                linguistic_payload["llmPending"] = True
    except Exception:
        logger.debug("stage_b_overlay_merge_failed", exc_info=True)

    from app.modules.privacy import assert_no_transcript_on_wire, strip_transcript_fields

    linguistic_payload = strip_transcript_fields(dict(linguistic_payload))
    # Drop keys pydantic LinguisticFamily forbids / can't coerce
    ask = linguistic_payload.get("ask")
    if isinstance(ask, dict):
        allowed_ask = {
            "type",
            "amount",
            "currency",
            "beneficiaryHint",
            "deadline",
            "sharesCredential",
            "beneficiaryMentioned",
        }
        linguistic_payload["ask"] = {k: v for k, v in ask.items() if k in allowed_ask}
        if "type" not in linguistic_payload["ask"]:
            linguistic_payload.pop("ask", None)
    cats = linguistic_payload.get("categories")
    if isinstance(cats, dict):
        linguistic_payload["categories"] = {
            str(k): float(v)
            for k, v in cats.items()
            if isinstance(v, (int, float)) and not isinstance(v, bool)
        }
    slow_ms = float(session.slow_path_latency_ms or 0.0)
    sr = session.ring_buffer.sample_rate
    window_end_ms = int(session.ring_buffer.total_samples_written * 1000 / sr)
    window_start_ms = max(0, window_end_ms - int(settings.window_seconds * 1000))
    frame = FeatureFrame(
        schema="sentinelvoice.FeatureFrame/1",
        sessionId=session.session_id,
        seq=state.emit_seq,
        windowStartMs=window_start_ms,
        windowEndMs=window_end_ms,
        channelProfile=session.profile,
        speechPresent=bool(extracted.get("speechPresent")),
        cumulativeSpeechMs=int(session.cumulative_speech_ms),
        voice=_family(VoiceFamily, extracted.get("voice") or {"available": False}),
        channel=_family(ChannelFamily, extracted.get("channel") or {"available": False}),
        prosody=_family(ProsodyFamily, extracted.get("prosody") or {"available": False}),
        speaker=_family(SpeakerFamily, extracted.get("speaker") or {"available": False}),
        watermark=_family(WatermarkFamily, extracted.get("watermark") or {"available": False}),
        linguistic=_family(LinguisticFamily, linguistic_payload),
        latencyMs=LatencyMs(fastPath=fast_ms, slowPath=slow_ms),
    )
    # Privacy guard before emit
    try:
        assert_no_transcript_on_wire(frame.model_dump(mode="json", exclude_none=True))
    except Exception:
        logger.exception("privacy_guard_failed session=%s — stripping linguistic text", session.session_id)
        linguistic_payload = strip_transcript_fields(linguistic_payload)
        linguistic_payload.pop("claimedIdentity", None)
        frame = frame.model_copy(
            update={"linguistic": _family(LinguisticFamily, linguistic_payload)}
        )
    session.emit_seq = state.emit_seq
    return frame


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
        written = session.ring_buffer.total_samples_written
        if written < needed:
            return None
        # Skip when the ring has not advanced — unless a keyword hit forced a push
        # so matchedKeywords reach Live Calls while PCM is briefly stalled.
        last_emitted = session.last_emitted_samples
        if written == last_emitted and not session.force_emit:
            return None
        window = session.ring_buffer.read_window(settings.window_seconds, hop_offset_s=0.0)
        # DSP releases the GIL; keep the event loop free.
        frame = await asyncio.to_thread(build_feature_frame, session, window)
        session.last_emitted_samples = written
        session.force_emit = False
        await self.emitter.emit(frame)
        logger.info(
            "feature_emit session_id=%s seq=%s fastPathMs=%.1f speechPresent=%s "
            "keywords=%s rules=%s thinking_chars=%s",
            session.session_id,
            frame.seq,
            frame.latencyMs.fastPath,
            frame.speechPresent,
            (frame.linguistic.matchedKeywords if frame.linguistic else None) or [],
            (frame.linguistic.matchedRuleIds if frame.linguistic else None) or [],
            len((frame.linguistic.llmThinking if frame.linguistic else None) or ""),
        )
        return frame

    async def _run(self, session_id: str) -> None:
        interval = settings.emit_interval_ms / 1000.0
        try:
            while True:
                try:
                    await self.tick(session_id)
                except asyncio.CancelledError:
                    raise
                except Exception:
                    # Fail-open: one bad window must not kill the session scheduler.
                    logger.exception("scheduler_tick_failed session_id=%s", session_id)
                await asyncio.sleep(interval)
        except asyncio.CancelledError:
            raise
