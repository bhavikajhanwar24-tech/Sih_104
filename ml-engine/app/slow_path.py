"""Slow-path orchestration — ASR + linguistic intent on its own asyncio cadence.

Runs every 2.5 s over a 6 s overlapping context window in a thread pool so the
500 ms fast path is never blocked (Context §6.2 / §7.2).
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Optional

import numpy as np

from app.config import settings
from app.modules import asr as asr_mod
from app.session import PipelineSession, SessionRegistry

logger = logging.getLogger("sentinelvoice.ml.slow_path")


class SlowPathRunner:
    """Per-session asyncio tasks; Whisper work stays on the ASR thread pool."""

    def __init__(self, registry: SessionRegistry) -> None:
        self.registry = registry
        self._tasks: dict[str, asyncio.Task[None]] = {}
        self._lock = asyncio.Lock()

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
        logger.info("slow_path_stop session_id=%s", session_id)

    async def stop_all(self) -> None:
        async with self._lock:
            ids = list(self._tasks)
        for session_id in ids:
            await self.stop(session_id)

    async def tick(self, session_id: str) -> Optional[asr_mod.AsrTickResult]:
        """One slow-path cycle: read 6 s window → thread-pool ASR → stash on session."""
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
        # Publish redacted linguistic snapshot for the 500 ms FeatureFrame builder.
        if result.available or session.asr_state.last_snippet_redacted:
            session.slow_path_linguistic = asr_mod.linguistic_from_state(
                session.asr_state
            )
        elif result.skipped_reason == "vad_gate":
            # Keep prior linguistic if any; ageMs will grow naturally.
            if session.slow_path_linguistic.get("available"):
                session.slow_path_linguistic = asr_mod.linguistic_from_state(
                    session.asr_state
                )
        logger.info(
            "slow_path_tick session_id=%s latency_ms=%.1f available=%s "
            "code_switch=%s skipped=%s",
            session.session_id,
            result.latency_ms,
            result.available,
            result.code_switch_detected,
            result.skipped_reason,
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


def measure_fast_path_while_asr(
    session: PipelineSession,
    window: np.ndarray,
    *,
    asr_audio: np.ndarray,
    iterations: int = 8,
) -> dict[str, float]:
    """Benchmark helper: run fast-path ticks concurrently with a blocking ASR job.

    Used by tests to prove fast-path p95 is unaffected by ASR thread-pool load.
    """
    from app.fast_path import extract

    latencies: list[float] = []
    barrier = time.perf_counter()

    def _asr_job() -> None:
        # Hold the ASR worker with a realistic-length call (or sleep if no model).
        try:
            asr_mod.transcribe_window(
                asr_audio, session.ring_buffer.sample_rate, session.asr_state
            )
        except Exception:
            time.sleep(0.2)

    fut = asr_mod.get_executor().submit(_asr_job)
    # Give the ASR thread a head start so it overlaps with fast-path work.
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
