from __future__ import annotations

import asyncio
import json
import os
from contextlib import suppress

import numpy as np
import pytest
from jsonschema.exceptions import ValidationError

os.environ.setdefault("SENTINELVOICE_ML_EMIT_ENABLED", "false")

from app.emitter import FeatureEmitter, frame_to_payload, validate_feature_frame
from app.fast_path import extract
from app.scheduler import SessionScheduler, build_feature_frame
from app.session import SessionRegistry
from app.types import ChannelProfile, FeatureFrame


def _loud_window(seconds: float = 2.0, sr: int = 16000) -> np.ndarray:
    t = np.arange(int(sr * seconds), dtype=np.float32) / sr
    return (0.4 * np.sin(2.0 * np.pi * 440.0 * t)).astype(np.float32)


def test_fast_path_stub_rms_is_deterministic() -> None:
    silent = np.zeros(16000, dtype=np.float32)
    loud = _loud_window(1.0)
    quiet = extract(silent, 16000, ChannelProfile.WEBRTC_WIDEBAND)
    voiced = extract(loud, 16000, ChannelProfile.WEBRTC_WIDEBAND)
    assert quiet["voice"]["available"] is True
    assert voiced["voice"]["available"] is True
    assert voiced["voice"]["spoofProbability"] > quiet["voice"]["spoofProbability"]
    assert quiet["prosody"] == {"available": False}
    assert quiet["channel"]["available"] is False
    assert quiet["speaker"]["available"] is False
    assert quiet["watermark"]["available"] is False
    assert quiet["linguistic"]["available"] is False


def test_built_frame_matches_frozen_schema() -> None:
    registry = SessionRegistry()
    session = registry.create("schema-sid", ChannelProfile.WEBRTC_WIDEBAND)
    session.ring_buffer.write(_loud_window(2.0))
    session.cumulative_speech_ms = 1500
    window = session.ring_buffer.read_window(2.0)
    frame = build_feature_frame(session, window)
    payload = frame_to_payload(frame)
    validate_feature_frame(payload)
    assert payload["schema"] == "sentinelvoice.FeatureFrame/1"
    assert payload["seq"] == 1
    assert "f0MeanHz" not in payload["prosody"]


def test_invalid_frame_fails_validation_loudly() -> None:
    with pytest.raises(ValidationError):
        validate_feature_frame({"schema": "nope"})


def test_queue_drops_oldest_and_counts() -> None:
    emitter = FeatureEmitter(url="ws://127.0.0.1:9/ws/features", queue_size=2, validate=False)
    emitter.enqueue({"seq": 1})
    emitter.enqueue({"seq": 2})
    emitter.enqueue({"seq": 3})
    assert emitter.dropped_frames == 1
    remaining = [json.loads(item)["seq"] for item in emitter._queue]
    assert remaining == [2, 3]


def test_scheduler_skips_until_two_seconds() -> None:
    async def _run() -> None:
        registry = SessionRegistry()
        emitter = FeatureEmitter(queue_size=8, validate=True)
        scheduler = SessionScheduler(registry, emitter)
        session = registry.create("short", ChannelProfile.VOIP_WIDEBAND)
        session.ring_buffer.write(_loud_window(1.0))
        assert await scheduler.tick("short") is None
        assert len(emitter._queue) == 0
        session.ring_buffer.write(_loud_window(1.0))
        frame = await scheduler.tick("short")
        assert frame is not None
        assert frame.seq == 1
        assert frame.latencyMs.fastPath >= 0
        validate_feature_frame(frame_to_payload(frame))
        assert len(emitter._queue) == 1

    asyncio.run(_run())


def test_emitter_sends_and_reconnects() -> None:
    async def _run() -> None:
        received: list[str] = []

        async def handler(ws) -> None:
            async for message in ws:
                received.append(str(message))

        import websockets

        async with websockets.serve(handler, "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            url = f"ws://127.0.0.1:{port}"
            emitter = FeatureEmitter(url=url, queue_size=8, validate=False, backoff_max_s=1.0)
            task = asyncio.create_task(emitter.run())
            try:
                await _wait_until(lambda: emitter.connected, timeout_s=2.0)
                await emitter.emit(_minimal_frame(1))
                await _wait_until(lambda: len(received) >= 1, timeout_s=2.0)
                server.close()
                await server.wait_closed()
                await emitter.emit(_minimal_frame(2))
                await _wait_until(lambda: emitter.connected is False, timeout_s=3.0)

                async with websockets.serve(handler, "127.0.0.1", port) as _restarted:
                    await _wait_until(lambda: emitter.connected, timeout_s=5.0)
                    await emitter.emit(_minimal_frame(3))
                    await _wait_until(lambda: len(received) >= 2, timeout_s=2.0)
            finally:
                await emitter.stop()
                task.cancel()
                with suppress(asyncio.CancelledError):
                    await task
        assert json.loads(received[0])["seq"] == 1
        assert any(json.loads(item)["seq"] == 3 for item in received)

    asyncio.run(_run())


def test_pydantic_alias_emits_schema_field() -> None:
    registry = SessionRegistry()
    session = registry.create("alias", ChannelProfile.PSTN_NARROWBAND)
    session.ring_buffer.write(_loud_window(2.0))
    frame = build_feature_frame(session, session.ring_buffer.read_window(2.0))
    payload = frame_to_payload(frame)
    assert "schema" in payload
    assert "schema_name" not in payload
    assert payload["channelProfile"] == "PSTN_NARROWBAND"


def _minimal_frame(seq: int) -> FeatureFrame:
    return FeatureFrame.model_validate(
        {
            "schema": "sentinelvoice.FeatureFrame/1",
            "sessionId": "ws-sid",
            "seq": seq,
            "windowStartMs": 0,
            "windowEndMs": 2000,
            "channelProfile": "WEBRTC_WIDEBAND",
            "speechPresent": False,
            "cumulativeSpeechMs": 0,
            "voice": {
                "available": True,
                "spoofProbability": 0.1,
                "modelId": "stub-rms-p2.2",
                "confidence": 0.4,
            },
            "channel": {"available": False},
            "prosody": {"available": False},
            "speaker": {"available": False},
            "watermark": {"available": False},
            "linguistic": {"available": False},
            "latencyMs": {"fastPath": 1.0, "slowPath": 0.0},
        }
    )


async def _wait_until(predicate, timeout_s: float) -> None:
    deadline = asyncio.get_event_loop().time() + timeout_s
    while asyncio.get_event_loop().time() < deadline:
        if predicate():
            return
        await asyncio.sleep(0.05)
    raise AssertionError("condition not met before timeout")
