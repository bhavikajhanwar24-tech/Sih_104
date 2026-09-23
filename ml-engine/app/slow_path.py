"""Slow-path orchestration — ASR + Stage A (sync) + Stage B LLM (async).

Cadence ~2.5 s over a 6 s overlapping PCM window. Stage A never waits on the LLM.
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
                "slow_path_start session_id=%s interval_ms=%s window_s=%s",
                session_id,
                settings.asr_interval_ms,
                settings.asr_window_seconds,
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
        logger.info("slow_path_stop session_id=%s", session_id)

    async def stop_all(self) -> None:
        async with self._lock:
            ids = list(self._tasks)
        for session_id in ids:
            await self.stop(session_id)

    async def tick(self, session_id: str) -> Optional[asr_mod.AsrTickResult]:
        """One slow-path cycle: PCM → ASR → Stage A → maybe schedule Stage B."""
        session = self.registry.get(session_id)
        if session is None:
            return None

        sr = session.ring_buffer.sample_rate
        needed = int(settings.asr_window_seconds * sr)
        if session.ring_buffer.total_samples_written < needed:
            return None

        window = session.ring_buffer.read_window(
            settings.asr_window_seconds, hop_offset_s=0.0
        )
        loop = asyncio.get_running_loop()
        result = await loop.run_in_executor(
            asr_mod.get_executor(),
            asr_mod.transcribe_window,
            window,
            sr,
            session.asr_state,
        )
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
            # Push a FeatureFrame even if the PCM ring has not advanced — otherwise
            # keyword hits never reach Live Calls when AudioSocket stalls.
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

        # Stage B: judge transcript against ACTIVE live rules. In lab mode, still run
        # LLM so operators see thinking even before a policy set is published.
        silence_end = False
        last_speech = self._last_speech_mono.get(session.session_id)
        if last_speech is not None and (time.monotonic() - last_speech) >= 0.6:
            if result.skipped_reason == "vad_gate" or not result.available:
                silence_end = True

        schedule_text = raw.strip()
        has_rules = bool(lexicon and getattr(lexicon, "rules", None))
        allow_stage_b = bool(schedule_text) and (has_rules or settings.lab_mode)
        if allow_stage_b:
            try:
                from app.llm_gateway.gateway import gateway as llm_gateway

                stage_b_runner.maybe_schedule(
                    session.session_id,
                    schedule_text,
                    stage_a=stage_a or run_stage_a(schedule_text, lexicon=lexicon),
                    lexicon=lexicon,
                    gateway=_GatewayAdapter(llm_gateway),
                    force=True,  # never wait on keywords
                )
            except Exception:
                logger.debug("stage_b_schedule_failed", exc_info=True)
        elif schedule_text and silence_end and not has_rules:
            logger.info(
                "stage_b_skip_no_active_rules session_id=%s raw_chars=%s",
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
        ):
            session.force_emit = True

        logger.info(
            "slow_path_tick session_id=%s latency_ms=%.1f available=%s "
            "code_switch=%s skipped=%s stage_a_ms=%.1f llm_pending=%s",
            session.session_id,
            result.latency_ms,
            result.available,
            result.code_switch_detected,
            result.skipped_reason,
            (stage_a.latency_ms if stage_a else 0.0),
            pending,
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
