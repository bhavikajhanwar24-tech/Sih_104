"""Smoke: AudioSocket client → bridge → ml-engine PCM ingest (fail-open covered)."""

from __future__ import annotations

import asyncio
import uuid
from unittest.mock import AsyncMock, patch

import numpy as np
import pytest

from gateway.asterisk_bridge import Metrics, MlEngineClient, handle_client
from gateway.protocol.audiosocket import FRAME_AUDIO, FRAME_TERMINATE, FRAME_UUID, encode_frame


@pytest.mark.asyncio
async def test_handle_client_opens_normalises_and_closes() -> None:
    uid = uuid.uuid4()
    # 20 ms SLIN16 silence
    audio = b"\x00\x00" * 160
    blob = (
        encode_frame(FRAME_UUID, uid.bytes)
        + encode_frame(FRAME_AUDIO, audio)
        + encode_frame(FRAME_TERMINATE, b"")
    )

    reader = asyncio.StreamReader()
    reader.feed_data(blob)
    reader.feed_eof()

    written = bytearray()

    class _Writer:
        def write(self, data: bytes) -> None:
            written.extend(data)

        async def drain(self) -> None:
            return None

        def get_extra_info(self, _name: str) -> tuple:
            return ("127.0.0.1", 12345)

        def close(self) -> None:
            return None

        async def wait_closed(self) -> None:
            return None

    metrics = Metrics()
    ml = MlEngineClient("http://ml.test", metrics)
    ml.open_session = AsyncMock(return_value=True)  # type: ignore[method-assign]
    ml.close_session = AsyncMock()  # type: ignore[method-assign]
    ml.push_pcm = AsyncMock(return_value=True)  # type: ignore[method-assign]

    with patch("gateway.asterisk_bridge.normalise") as norm:
        pcm = np.zeros(320, dtype=np.float32)
        norm.return_value = (pcm, "PSTN_NARROWBAND")
        await handle_client(reader, _Writer(), ml, metrics)  # type: ignore[arg-type]

    ml.open_session.assert_awaited_once_with(str(uid))
    ml.push_pcm.assert_awaited()
    ml.close_session.assert_awaited_once_with(str(uid))
    # Echoed audio frame back to Asterisk
    assert FRAME_AUDIO in written
    snap = await metrics.snapshot()
    assert snap["frames_received"] == 1


@pytest.mark.asyncio
async def test_fail_open_when_ml_down() -> None:
    uid = uuid.uuid4()
    audio = b"\x00\x00" * 80
    blob = (
        encode_frame(FRAME_UUID, uid.bytes)
        + encode_frame(FRAME_AUDIO, audio)
        + encode_frame(FRAME_TERMINATE, b"")
    )
    reader = asyncio.StreamReader()
    reader.feed_data(blob)
    reader.feed_eof()

    class _Writer:
        def write(self, data: bytes) -> None:
            return None

        async def drain(self) -> None:
            return None

        def get_extra_info(self, _name: str) -> tuple:
            return ("127.0.0.1", 1)

        def close(self) -> None:
            return None

        async def wait_closed(self) -> None:
            return None

    metrics = Metrics()
    ml = MlEngineClient("http://ml.test", metrics)
    ml.open_session = AsyncMock(return_value=False)  # type: ignore[method-assign]
    ml.close_session = AsyncMock()  # type: ignore[method-assign]
    ml.push_pcm = AsyncMock(return_value=True)  # type: ignore[method-assign]

    with patch("gateway.asterisk_bridge.normalise") as norm:
        norm.return_value = (np.zeros(160, dtype=np.float32), "PSTN_NARROWBAND")
        await handle_client(reader, _Writer(), ml, metrics)  # type: ignore[arg-type]

    ml.push_pcm.assert_not_awaited()
    ml.close_session.assert_not_awaited()
    snap = await metrics.snapshot()
    assert snap["drops"] >= 1
    assert snap["degraded"] is True
