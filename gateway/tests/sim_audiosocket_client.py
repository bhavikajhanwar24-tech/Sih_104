"""Simulate an Asterisk AudioSocket client against a local bridge."""

from __future__ import annotations

import array
import asyncio
import uuid

import httpx

from gateway.protocol.audiosocket import (
    FRAME_AUDIO,
    FRAME_TERMINATE,
    FRAME_UUID,
    encode_frame,
)


async def main() -> None:
    uid = uuid.uuid4()
    samples = array.array("h", [int(5000 * ((i % 40) / 20 - 1)) for i in range(800)])
    payload = samples.tobytes()
    reader, writer = await asyncio.open_connection("127.0.0.1", 9092)
    writer.write(encode_frame(FRAME_UUID, uid.bytes))
    await writer.drain()
    for _ in range(10):
        writer.write(encode_frame(FRAME_AUDIO, payload))
        await writer.drain()
        # Echo from bridge (optional read) — drain quietly
        await asyncio.sleep(0.05)
    writer.write(encode_frame(FRAME_TERMINATE, b""))
    await writer.drain()
    writer.close()
    await writer.wait_closed()
    await asyncio.sleep(0.5)
    async with httpx.AsyncClient() as client:
        health = await client.get("http://127.0.0.1:8000/health")
        print("health", health.json())
    print("sim_ok", uid)


if __name__ == "__main__":
    asyncio.run(main())
