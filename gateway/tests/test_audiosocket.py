"""
Unit tests for the Asterisk AudioSocket frame codec.

Hand-built byte sequences + feeder that splits bytes across awaits.
"""

from __future__ import annotations

import asyncio
import uuid

import pytest

from gateway.protocol.audiosocket import (
    FRAME_AUDIO,
    FRAME_ERROR,
    FRAME_TERMINATE,
    FRAME_UUID,
    AudioSocketError,
    encode_frame,
    read_frame,
    write_audio,
    write_frame,
)


@pytest.mark.asyncio
async def test_encode_roundtrip_terminate() -> None:
    raw = encode_frame(FRAME_TERMINATE, b"")
    assert raw == b"\x00\x00\x00"
    reader = asyncio.StreamReader()
    reader.feed_data(raw)
    reader.feed_eof()
    frame = await read_frame(reader)
    assert frame.is_terminate
    assert frame.payload == b""


@pytest.mark.asyncio
async def test_uuid_frame_16_bytes() -> None:
    uid = uuid.uuid4()
    raw = encode_frame(FRAME_UUID, uid.bytes)
    assert raw[0] == FRAME_UUID
    assert int.from_bytes(raw[1:3], "big") == 16
    reader = asyncio.StreamReader()
    reader.feed_data(raw)
    reader.feed_eof()
    frame = await read_frame(reader)
    assert frame.is_uuid
    assert frame.payload == uid.bytes
    assert uuid.UUID(bytes=frame.payload) == uid


@pytest.mark.asyncio
async def test_audio_frame_slin16() -> None:
    payload = bytes(range(256)) + bytes(range(64))
    assert len(payload) == 320
    raw = encode_frame(FRAME_AUDIO, payload)
    reader = asyncio.StreamReader()
    reader.feed_data(raw)
    reader.feed_eof()
    frame = await read_frame(reader)
    assert frame.is_audio
    assert frame.payload == payload


@pytest.mark.asyncio
async def test_error_frame() -> None:
    raw = encode_frame(FRAME_ERROR, b"boom")
    reader = asyncio.StreamReader()
    reader.feed_data(raw)
    reader.feed_eof()
    frame = await read_frame(reader)
    assert frame.is_error
    assert frame.payload == b"boom"


@pytest.mark.asyncio
async def test_split_across_reads_header_and_payload() -> None:
    """TCP can deliver the 3-byte header and payload in arbitrary fragments."""
    payload = b"\x01\x02\x03\x04\x05"
    full = encode_frame(FRAME_AUDIO, payload)
    chunks = [full[:1], full[1:3], full[3:5], full[5:]]
    assert b"".join(chunks) == full

    reader = asyncio.StreamReader()

    async def feeder() -> None:
        for chunk in chunks:
            reader.feed_data(chunk)
            await asyncio.sleep(0)
        reader.feed_eof()

    feed_task = asyncio.create_task(feeder())
    frame = await read_frame(reader)
    await feed_task
    assert frame.type == FRAME_AUDIO
    assert frame.payload == payload


@pytest.mark.asyncio
async def test_split_length_bytes_only() -> None:
    payload = b"abcdefgh"
    full = encode_frame(FRAME_AUDIO, payload)
    chunks = [full[0:2], full[2:3], full[3:]]
    reader = asyncio.StreamReader()

    async def feeder() -> None:
        for chunk in chunks:
            reader.feed_data(chunk)
            await asyncio.sleep(0)
        reader.feed_eof()

    feed_task = asyncio.create_task(feeder())
    frame = await read_frame(reader)
    await feed_task
    assert frame.payload == payload


@pytest.mark.asyncio
async def test_incomplete_header_raises() -> None:
    reader = asyncio.StreamReader()
    reader.feed_data(b"\x10\x00")
    reader.feed_eof()
    with pytest.raises(AudioSocketError, match="incomplete header"):
        await read_frame(reader)


@pytest.mark.asyncio
async def test_write_audio_and_write_frame() -> None:
    class _Buf:
        def __init__(self) -> None:
            self.data = bytearray()

        def write(self, b: bytes) -> None:
            self.data.extend(b)

        async def drain(self) -> None:
            return None

    buf = _Buf()
    await write_frame(buf, FRAME_TERMINATE, b"")  # type: ignore[arg-type]
    await write_audio(buf, b"\x00\x01")  # type: ignore[arg-type]
    assert bytes(buf.data[:3]) == b"\x00\x00\x00"
    assert buf.data[3] == FRAME_AUDIO
    assert int.from_bytes(buf.data[4:6], "big") == 2
    assert buf.data[6:8] == b"\x00\x01"


@pytest.mark.asyncio
async def test_read_timeout() -> None:
    reader = asyncio.StreamReader()
    with pytest.raises(asyncio.TimeoutError):
        await read_frame(reader, timeout=0.05)
