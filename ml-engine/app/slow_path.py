"""Slow-path orchestration — ASR + Stage A (sync) + Stage B LLM (async).

Hop ASR (~3 s of newest audio every ~3 s). Stage A never waits on the LLM.
Stage B runs at most once per 10 s and only when the transcript actually changed.
Transcript text stays in-process; FeatureFrame linguistic is numbers/enums only (F11).
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Optional

import numpy as np

from app.config import settings
from app.modules import asr as asr_mod
from app.modules import tenant_lexicon
from app.modules.stage_a import run_stage_a
from app.modules.stage_b import stage_b_runner
from app.session import PipelineSession, SessionRegistry

logger = logging.getLogger("sentinelvoice.ml.slow_path")


class SlowPathRunner:
    """Per-session asyncio tasks; Whisper work stays on the ASR thread pool."""

    def __init__(self, registry: SessionRegistry) -> None:
        self.registry = registry
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._lock = asyncio.Lock()
        self._last_speech_mono: dict[str, float] = {}
        self._last_llm_text_norm: dict[str, str] = {}

    async def start(self, session_id: str) -> None:
        if not settings.asr_enabled:
            return
        async with self._lock:
            existing = self._tasks.get(session_id)
            if existing is not None and not existing.done():
                return
            self._tasks[session_id] = asyncio.create_task(
                self._run(session_id),
                name=f"slow-path-{session_id}",
            )
            logger.info(
                "slow_path_start session_id=%s interval_ms=%s window_s=%s model=%s",
                session_id,
                settings.asr_interval_ms,
                settings.asr_window_seconds,
                settings.asr_model_size,
            )

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
        session = self.registry.get(session_id)
        if session is not None:
            asr_mod.reset_session_state(session.asr_state)
            session.slow_path_latency_ms = 0.0
            session.slow_path_linguistic = {"available": False}
        stage_b_runner.clear(session_id)
        self._last_speech_mono.pop(session_id, None)
        self._last_llm_text_norm.pop(session_id, None)
        logger.info("slow_path_stop session_id=%s", session_id)

    async def stop_all(self) -> None:
        async with self._lock:
            ids = list(self._tasks)
        for session_id in ids:
            await self.stop(session_id)

    def _read_hop_window(self, session: PipelineSession) -> Optional[np.ndarray]:
        """Read only newest audio since the last ASR cursor (+ short overlap)."""
        ring = session.ring_buffer
        sr = ring.sample_rate
        state = session.asr_state
        total = int(ring.total_samples_written)
        overlap = int(max(0.0, float(settings.asr_hop_overlap_seconds)) * sr)
        max_win = int(float(settings.asr_window_seconds) * sr)
        min_new = max(int(0.6 * sr), int(0.25 * max_win))

        cursor = int(getattr(state, "_asr_cursor_samples", 0) or 0)
        if cursor > total:
            cursor = 0
            state._asr_cursor_samples = 0

        new_samples = total - cursor
        if cursor == 0:
            # First tick: need a short window before we start.
            if total < max(min_new, int(1.2 * sr)):
                return None
            duration_s = min(float(settings.asr_window_seconds), total / float(sr))
        else:
            if new_samples < min_new:
                return None
            take = min(max_win, new_samples + overlap)
            duration_s = take / float(sr)

        if duration_s <= 0.05:
            return None
        return ring.read_window(duration_s, hop_offset_s=0.0)

    async def tick(self, session_id: str) -> Optional[asr_mod.AsrTickResult]:
        """One slow-path cycle: hop PCM → ASR → Stage A → maybe schedule Stage B."""
        session = self.registry.get(session_id)
        if session is None:
            return None

        window = self._read_hop_window(session)
        if window is None or window.size == 0:
            return None

        sr = session.ring_buffer.sample_rate
        cursor_before = int(getattr(session.asr_state, "_asr_cursor_samples", 0) or 0)
        total_before = int(session.ring_buffer.total_samples_written)

        loop = asyncio.get_running_loop()
        result = await loop.run_in_executor(
            asr_mod.get_executor(),
            asr_mod.transcribe_window,
            window,
            sr,
            session.asr_state,
        )
        # Advance cursor past the audio we just heard (keep overlap for next hop).
        overlap = int(max(0.0, float(settings.asr_hop_overlap_seconds)) * sr)
        session.asr_state._asr_cursor_samples = max(
            cursor_before, total_before - overlap
        )
        # If ring advanced during ASR, don't leave a permanent lag larger than one window.
        total_now = int(session.ring_buffer.total_samples_written)
        max_lag = int(float(settings.asr_window_seconds) * sr)
        if total_now - session.asr_state._asr_cursor_samples > max_lag:
            session.asr_state._asr_cursor_samples = total_now - max_lag

        self._apply_result(session, result)
        return result

    def _apply_result(
        self, session: PipelineSession, result: asr_mod.AsrTickResult
    ) -> None:
        session.slow_path_latency_ms = float(result.latency_ms)
        lexicon = tenant_lexicon.get_lexicon(session.tenant_id)
        raw = asr_mod.raw_rolling_text(session.asr_state)

        if raw.strip() or float(getattr(result, "speech_ratio", 0.0) or 0.0) >= 0.2:
            self._last_speech_mono[session.session_id] = time.monotonic()

        stage_a = run_stage_a(raw, lexicon=lexicon) if raw.strip() else None
        overlay = stage_b_runner.latest(session.session_id)
        pending = stage_b_runner.pending(session.session_id)

        if stage_a is not None and (stage_a.matched_keywords or stage_a.matched_rule_ids):
            logger.info(
                "keyword_hit session_id=%s terms=%s rules=%s raw_chars=%s lexicon_kw=%s",
                session.session_id,
                stage_a.matched_keywords[:8],
                stage_a.matched_rule_ids[:8],
                len(raw),
                len(lexicon.keywords) if lexicon else 0,
            )
            session.force_emit = True
        elif raw.strip():
            logger.info(
                "stage_a_no_keyword_hit session_id=%s raw_chars=%s lexicon_kw=%s sample=%r",
                session.session_id,
                len(raw),
                len(lexicon.keywords) if lexicon else 0,
                (raw[:80] + "…") if len(raw) > 80 else raw,
            )

        if result.available or session.asr_state._rolling_raw.strip():
            session.slow_path_linguistic = asr_mod.linguistic_from_state(
                session.asr_state,
                lexicon=lexicon,
                stage_b_overlay=overlay,
                llm_pending=pending,
            )
        elif result.skipped_reason == "vad_gate":
            if session.slow_path_linguistic.get("available"):
                session.slow_path_linguistic = asr_mod.linguistic_from_state(
                    session.asr_state,
                    lexicon=lexicon,
                    stage_b_overlay=overlay,
                    llm_pending=pending,
                )

        # Stage B: only when transcript meaningfully changed; 10 s cooldown in runner.
        silence_end = False
        last_speech = self._last_speech_mono.get(session.session_id)
        if last_speech is not None and (time.monotonic() - last_speech) >= 0.6:
            if result.skipped_reason == "vad_gate" or not result.available:
                silence_end = True

        schedule_text = raw.strip()
        schedule_norm = " ".join(schedule_text.lower().split())
        prev_llm_norm = self._last_llm_text_norm.get(session.session_id, "")
        text_changed = bool(schedule_norm) and schedule_norm != prev_llm_norm
        has_rules = bool(lexicon and getattr(lexicon, "rules", None))
        allow_stage_b = text_changed and (has_rules or settings.lab_mode)
        if allow_stage_b:
            try:
                from app.llm_gateway.gateway import gateway as llm_gateway

                queued = stage_b_runner.maybe_schedule(
                    session.session_id,
                    schedule_text,
                    stage_a=stage_a or run_stage_a(schedule_text, lexicon=lexicon),
                    gateway=_GatewayAdapter(llm_gateway),
                    lexicon=lexicon,
                    force=False,
                )
                # Always remember the caption we just evaluated so identical text
                # never re-enters the scheduler (queued or skipped-as-unchanged).
                if queued or schedule_norm:
                    self._last_llm_text_norm[session.session_id] = schedule_norm
            except Exception:
                logger.debug("stage_b_schedule_failed", exc_info=True)
        elif schedule_text and silence_end and not has_rules and not settings.lab_mode:
            logger.info(
                "stage_b_skip_no_active_rules session_id=%s raw_chars=%s",
                session.session_id,
                len(schedule_text),
            )
        elif schedule_text and not text_changed:
            logger.debug(
                "stage_b_skip_same_text session_id=%s chars=%s",
                session.session_id,
                len(schedule_text),
            )

        # Push frames when captions, keywords, or LLM-matched rules update.
        ling_now = session.slow_path_linguistic or {}
        if (
            ling_now.get("matchedKeywords")
            or ling_now.get("matchedRuleIds")
            or ling_now.get("llmThinking")
            or ling_now.get("redactedSnippet")
            or ling_now.get("redactedDelta")
            or pending
            or stage_b_runner.pending(session.session_id)
        ):
            session.force_emit = True

        logger.info(
            "slow_path_tick session_id=%s latency_ms=%.1f available=%s "
            "code_switch=%s skipped=%s stage_a_ms=%.1f llm_pending=%s text_changed=%s",
            session.session_id,
            result.latency_ms,
            result.available,
            result.code_switch_detected,
            result.skipped_reason,
            (stage_a.latency_ms if stage_a else 0.0),
            stage_b_runner.pending(session.session_id),
            text_changed,
        )

    async def _run(self, session_id: str) -> None:
        interval = settings.asr_interval_ms / 1000.0
        try:
            while True:
                try:
                    await self.tick(session_id)
                except asyncio.CancelledError:
                    raise
                except Exception:
                    logger.exception(
                        "slow_path_tick_failed session_id=%s", session_id
                    )
                await asyncio.sleep(interval)
        except asyncio.CancelledError:
            raise


class _GatewayAdapter:
    """Adapt LlmGateway.complete_json to StageBRunner's expected .run() shape."""

    def __init__(self, gateway: object) -> None:
        self._gw = gateway

    async def run(
        self,
        *,
        task: str,
        system: str,
        user: dict,
        schema: dict,
    ) -> dict:
        import json

        user_s = json.dumps(user, ensure_ascii=False)
        return await self._gw.complete_json(  # type: ignore[attr-defined]
            task=task,
            system=system,
            user=user_s,
            json_schema=schema,
            allow_external_llm=False,
        )


def measure_fast_path_while_asr(
    session: PipelineSession,
    window: np.ndarray,
    *,
    asr_audio: np.ndarray,
    iterations: int = 8,
) -> dict[str, float]:
    """Benchmark helper: run fast-path ticks concurrently with a blocking ASR job."""
    from app.fast_path import extract

    latencies: list[float] = []
    barrier = time.perf_counter()

    def _asr_job() -> None:
        try:
            asr_mod.transcribe_window(
                asr_audio, session.ring_buffer.sample_rate, session.asr_state
            )
        except Exception:
            time.sleep(0.2)

    fut = asr_mod.get_executor().submit(_asr_job)
    time.sleep(0.01)
    for _ in range(iterations):
        t0 = time.perf_counter()
        extract(
            window,
            session.ring_buffer.sample_rate,
            session.profile,
            session_state=session.fast_path,
        )
        latencies.append((time.perf_counter() - t0) * 1000.0)
    fut.result(timeout=120.0)
    arr = np.asarray(latencies, dtype=np.float64)
    return {
        "p50_ms": float(np.percentile(arr, 50)),
        "p95_ms": float(np.percentile(arr, 95)),
        "mean_ms": float(np.mean(arr)),
        "n": float(len(latencies)),
        "elapsed_s": time.perf_counter() - barrier,
    }
