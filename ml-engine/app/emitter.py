from __future__ import annotations

import asyncio
import json
import logging
from collections import deque
from pathlib import Path
from typing import Any, Optional

from app.config import settings
from app.types import FeatureFrame

logger = logging.getLogger("sentinelvoice.ml.emitter")

_SCHEMA_CACHE: dict[str, Any] | None = None


def schema_path() -> Path:
    return Path(__file__).resolve().parents[2] / "docs" / "contracts" / "FeatureFrame.schema.json"


def load_feature_frame_schema() -> dict[str, Any]:
    global _SCHEMA_CACHE
    if _SCHEMA_CACHE is None:
        path = schema_path()
        _SCHEMA_CACHE = json.loads(path.read_text(encoding="utf-8"))
    return _SCHEMA_CACHE


def frame_to_payload(frame: FeatureFrame) -> dict[str, Any]:
    return frame.model_dump(mode="json", by_alias=True, exclude_none=True)


def validate_feature_frame(payload: dict[str, Any]) -> None:
    from jsonschema import Draft202012Validator

    Draft202012Validator(load_feature_frame_schema()).validate(payload)


class FeatureEmitter:
    """Resilient WS client to the Java FeatureFrame ingest endpoint."""

    def __init__(
        self,
        url: Optional[str] = None,
        queue_size: Optional[int] = None,
        validate: Optional[bool] = None,
        backoff_max_s: Optional[float] = None,
    ) -> None:
        self.url = url if url is not None else settings.java_ingest_ws
        self.queue_size = queue_size if queue_size is not None else settings.emit_queue_max
        self.validate = (
            validate if validate is not None else (settings.validate_frames or settings.debug)
        )
        self.backoff_max_s = (
            backoff_max_s if backoff_max_s is not None else settings.emit_backoff_max_s
        )
        self._queue: deque[str] = deque()
        self._waiter: asyncio.Event = asyncio.Event()
        self.dropped_frames = 0
        self.sent_frames = 0
        self.connected = False
        self._running = False
        self._send_task: asyncio.Task[None] | None = None

    def enqueue(self, payload: dict[str, Any] | str) -> None:
        text = payload if isinstance(payload, str) else json.dumps(payload, separators=(",", ":"))
        if len(self._queue) >= self.queue_size:
            self._queue.popleft()
            self.dropped_frames += 1
            logger.warning(
                "emit_drop_oldest queue=%s dropped=%s",
                self.queue_size,
                self.dropped_frames,
            )
        self._queue.append(text)
        self._waiter.set()

    async def emit(self, frame: FeatureFrame) -> None:
        payload = frame_to_payload(frame)
        if self.validate:
            validate_feature_frame(payload)
        self.enqueue(payload)

    async def run(self) -> None:
        import websockets

        self._running = True
        backoff = 0.25
        logger.info("emitter_start url=%s queue_max=%s", self.url, self.queue_size)
        while self._running:
            try:
                async with websockets.connect(self.url, open_timeout=2, ping_interval=20) as ws:
                    self.connected = True
                    backoff = 0.25
                    logger.info("emitter_connected url=%s", self.url)
                    while self._running:
                        if not self._queue:
                            self._waiter.clear()
                            try:
                                await asyncio.wait_for(self._waiter.wait(), timeout=1.0)
                            except asyncio.TimeoutError:
                                continue
                        if not self._queue:
                            continue
                        message = self._queue.popleft()
                        await ws.send(message)
                        self.sent_frames += 1
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self.connected = False
                logger.warning(
                    "emitter_disconnected url=%s backoff_s=%.2f error=%s",
                    self.url,
                    backoff,
                    type(exc).__name__,
                )
                if not self._running:
                    break
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2.0, self.backoff_max_s)
        self.connected = False

    async def stop(self) -> None:
        self._running = False
        self.connected = False
        self._waiter.set()
