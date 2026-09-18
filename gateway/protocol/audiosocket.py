"""
Asterisk AudioSocket wire protocol (Context §11.3).

Frame layout: [type:1][length:2 big-endian][payload:length]

  0x00 TERMINATE
  0x01 UUID      — 16 raw bytes
  0x10 AUDIO     — SLIN16, 8 kHz mono
  0xff ERROR
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Optional

FRAME_TERMINATE = 0x00
FRAME_UUID = 0x01
FRAME_AUDIO = 0x10
FRAME_ERROR = 0xFF


class AudioSocketError(Exception):
    """Protocol or I/O error on an AudioSocket stream."""


@dataclass(frozen=True, slots=True)
class Frame:
    type: int
    payload: bytes

    @property
    def is_terminate(self) -> bool:
        return self.type == FRAME_TERMINATE

    @property
    def is_uuid(self) -> bool:
        return self.type == FRAME_UUID

    @property
    def is_audio(self) -> bool:
        return self.type == FRAME_AUDIO

    @property
    def is_error(self) -> bool:
        return self.type == FRAME_ERROR


def encode_frame(frame_type: int, payload: bytes = b"") -> bytes:
    if len(payload) > 0xFFFF:
        raise AudioSocketError(f"payload too large: {len(payload)}")
    return bytes((frame_type & 0xFF,)) + len(payload).to_bytes(2, "big") + payload


async def read_frame(
    reader: asyncio.StreamReader,
    *,
    timeout: Optional[float] = None,
) -> Frame:
    """
    Read one AudioSocket frame.

    Uses readexactly so a header/payload split across TCP segments is handled
    correctly (never interpret a partial length as a full frame).
    """

    async def _readexactly(n: int) -> bytes:
        return await reader.readexactly(n)

    try:
        if timeout is None:
            header = await _readexactly(3)
        else:
            header = await asyncio.wait_for(_readexactly(3), timeout=timeout)
    except asyncio.IncompleteReadError as exc:
        if not exc.partial:
            raise
        raise AudioSocketError(
            f"incomplete header ({len(exc.partial)}/3 bytes)"
        ) from exc

    frame_type = header[0]
    length = int.from_bytes(header[1:3], "big")

    if length == 0:
        payload = b""
    else:
        try:
            if timeout is None:
                payload = await _readexactly(length)
            else:
                payload = await asyncio.wait_for(_readexactly(length), timeout=timeout)
        except asyncio.IncompleteReadError as exc:
            raise AudioSocketError(
                f"incomplete payload ({len(exc.partial)}/{length} bytes)"
            ) from exc

    return Frame(type=frame_type, payload=payload)


async def write_frame(
    writer: asyncio.StreamWriter,
    frame_type: int,
    payload: bytes = b"",
) -> None:
    """Send one AudioSocket frame (e.g. whisper / pass-through audio)."""
    writer.write(encode_frame(frame_type, payload))
    await writer.drain()


async def write_audio(writer: asyncio.StreamWriter, pcm_slin16: bytes) -> None:
    """Convenience: send an AUDIO (0x10) frame of SLIN16 samples."""
    await write_frame(writer, FRAME_AUDIO, pcm_slin16)
