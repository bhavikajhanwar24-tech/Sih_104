#!/usr/bin/env python3
"""
Replay a WAV into the ml-engine ingest WebSocket at REAL-TIME pace (P11.1).

The demo gauge must evolve watchably — never blast frames as fast as the socket
will accept. Default cadence: 500 ms of PCM every 500 ms of wall-clock time.

Usage (repo root, ml-engine venv):
  python gateway/replay_audio.py --wav scenarios/audio/02-deepfake-ceo-wire.wav \\
      --session <sid> --ws ws://127.0.0.1:8000/ingest/<sid>

  python gateway/replay_audio.py --wav scenarios/audio/06-adversarial-evasion.wav \\
      --session <sid> --profile pcm_mulaw_8k --loop

  python gateway/replay_audio.py --generate-placeholder \\
      --wav scenarios/audio/01-legit-cfo.wav
"""

from __future__ import annotations

import argparse
import asyncio
import audioop
import json
import logging
import math
import struct
import sys
import time
import wave
from pathlib import Path
from typing import Optional

LOG = logging.getLogger("sentinelvoice.replay_audio")

TARGET_RATE = 16_000
FRAME_MS = 500
FRAME_SAMPLES = TARGET_RATE * FRAME_MS // 1000  # 8000
FRAME_BYTES = FRAME_SAMPLES * 2

# P4.3 codec fixture names → on-the-fly degrade hints
PROFILE_ALIASES = {
    "pcm_mulaw_8k": "mulaw",
    "pcm_alaw_8k": "alaw",
    "mulaw": "mulaw",
    "alaw": "alaw",
    "narrowband": "mulaw",
    "none": None,
    "pcm_s16le": None,
}


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def generate_placeholder_wav(path: Path, duration_s: float = 30.0, sr: int = TARGET_RATE) -> Path:
    """Speech-like harmonic stack with pauses — enough for rehearsal without real audio."""
    path.parent.mkdir(parents=True, exist_ok=True)
    n = int(sr * duration_s)
    with wave.open(str(path), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        frames = bytearray()
        for i in range(n):
            t = i / sr
            if int(t * 2) % 2 == 0:
                x = 0.22 * math.sin(2 * math.pi * 140 * t)
                x += 0.10 * math.sin(2 * math.pi * 280 * t)
                x += 0.05 * math.sin(2 * math.pi * 420 * t)
                x += 0.02 * math.sin(2 * math.pi * 50 * t)
            else:
                x = 0.008 * math.sin(2 * math.pi * 40 * t)
            frames += struct.pack("<h", int(max(-1.0, min(1.0, x)) * 32767))
        w.writeframes(frames)
    LOG.info("Wrote placeholder WAV %s (%.1fs @ %d Hz)", path, duration_s, sr)
    return path


def _read_wav_mono_pcm16(path: Path) -> tuple[bytes, int]:
    with wave.open(str(path), "rb") as w:
        channels = w.getnchannels()
        sampwidth = w.getsampwidth()
        rate = w.getframerate()
        nframes = w.getnframes()
        raw = w.readframes(nframes)

    if sampwidth != 2:
        raise ValueError(f"Only 16-bit PCM WAV supported (got sampwidth={sampwidth})")

    if channels == 2:
        raw = audioop.tomono(raw, 2, 0.5, 0.5)
    elif channels != 1:
        raise ValueError(f"Unsupported channel count: {channels}")

    if rate != TARGET_RATE:
        raw, _ = audioop.ratecv(raw, 2, 1, rate, TARGET_RATE, None)
        rate = TARGET_RATE

    return raw, rate


def _mulaw_roundtrip(pcm16: bytes) -> bytes:
    """Simulate G.711 µ-law narrowband degrade (P4.3-style) in-process."""
    # Down to 8 kHz → µ-law → linear → back to 16 kHz
    pcm8k, _ = audioop.ratecv(pcm16, 2, 1, TARGET_RATE, 8000, None)
    mulaw = audioop.lin2ulaw(pcm8k, 2)
    linear8k = audioop.ulaw2lin(mulaw, 2)
    pcm16_back, _ = audioop.ratecv(linear8k, 2, 1, 8000, TARGET_RATE, None)
    return pcm16_back


def _alaw_roundtrip(pcm16: bytes) -> bytes:
    pcm8k, _ = audioop.ratecv(pcm16, 2, 1, TARGET_RATE, 8000, None)
    alaw = audioop.lin2alaw(pcm8k, 2)
    linear8k = audioop.alaw2lin(alaw, 2)
    pcm16_back, _ = audioop.ratecv(linear8k, 2, 1, 8000, TARGET_RATE, None)
    return pcm16_back


def apply_profile(pcm16: bytes, profile: Optional[str]) -> tuple[bytes, str, int]:
    """
    Returns (pcm_bytes, encoding_for_hello, sample_rate_for_hello).

    When profile forces mulaw/alaw we still send pcm_s16le at 16 kHz after
    round-trip degrade so the ml-engine normaliser sees telephony artifacts
    without requiring a separate ingest encoding path.
    """
    if not profile:
        return pcm16, "pcm_s16le", TARGET_RATE
    kind = PROFILE_ALIASES.get(profile, profile)
    if kind is None:
        return pcm16, "pcm_s16le", TARGET_RATE
    if kind == "mulaw":
        return _mulaw_roundtrip(pcm16), "pcm_s16le", TARGET_RATE
    if kind == "alaw":
        return _alaw_roundtrip(pcm16), "pcm_s16le", TARGET_RATE
    raise ValueError(
        f"Unknown --profile {profile!r}. Known: {sorted(PROFILE_ALIASES)}"
    )


def _frame_iter(pcm16: bytes):
    # Pad final partial frame with silence so duration stays exact.
    if len(pcm16) % FRAME_BYTES:
        pcm16 = pcm16 + b"\x00" * (FRAME_BYTES - (len(pcm16) % FRAME_BYTES))
    for off in range(0, len(pcm16), FRAME_BYTES):
        yield pcm16[off : off + FRAME_BYTES]


async def stream_once(
    ws_url: str,
    session_id: str,
    pcm16: bytes,
    encoding: str,
    sample_rate: int,
    channel: str,
    realtime: bool,
) -> int:
    try:
        import websockets
    except ImportError as exc:
        raise SystemExit(
            "websockets package required. Activate the ml-engine venv or: pip install websockets"
        ) from exc

    hello = {
        "sessionId": session_id,
        "sampleRate": sample_rate,
        "encoding": encoding,
        "channel": channel,
        "channels": 1,
        "source": "replay",
    }

    frames_sent = 0
    async with websockets.connect(ws_url, max_size=2**22) as ws:
        await ws.send(json.dumps(hello))
        LOG.info("Hello sent sessionId=%s encoding=%s rate=%d", session_id, encoding, sample_rate)

        t0 = time.perf_counter()
        for i, frame in enumerate(_frame_iter(pcm16)):
            if realtime:
                target = t0 + (i * FRAME_MS / 1000.0)
                delay = target - time.perf_counter()
                if delay > 0:
                    await asyncio.sleep(delay)
            await ws.send(frame)
            frames_sent += 1
            if frames_sent % 10 == 0:
                elapsed = time.perf_counter() - t0
                LOG.info(
                    "paced frames=%d elapsed=%.1fs audio_s=%.1f",
                    frames_sent,
                    elapsed,
                    frames_sent * FRAME_MS / 1000.0,
                )

    return frames_sent


async def run(args: argparse.Namespace) -> int:
    wav_path = Path(args.wav)
    if args.generate_placeholder:
        generate_placeholder_wav(wav_path, duration_s=args.duration)
        if not args.session:
            return 0

    if not wav_path.is_file():
        LOG.error("WAV not found: %s (use --generate-placeholder)", wav_path)
        return 2

    pcm, rate = _read_wav_mono_pcm16(wav_path)
    pcm, encoding, out_rate = apply_profile(pcm, args.profile)

    ws_url = args.ws
    if not ws_url:
        if not args.session:
            LOG.error("--session or --ws is required for streaming")
            return 2
        ws_url = f"{args.ws_base.rstrip('/')}/{args.session}"

    session_id = args.session or "replay-session"
    # Derive session id from URL path if not given
    if args.session is None and "/ingest/" in ws_url:
        session_id = ws_url.rstrip("/").split("/")[-1]

    channel = args.channel
    loops = 0
    while True:
        loops += 1
        LOG.info(
            "Replay start loop=%d wav=%s profile=%s realtime=%s → %s",
            loops,
            wav_path,
            args.profile or "none",
            not args.fast,
            ws_url,
        )
        n = await stream_once(
            ws_url=ws_url,
            session_id=session_id,
            pcm16=pcm,
            encoding=encoding,
            sample_rate=out_rate,
            channel=channel,
            realtime=not args.fast,
        )
        LOG.info("Replay done loop=%d frames=%d (%.1fs audio)", loops, n, n * FRAME_MS / 1000.0)
        if not args.loop:
            break
        LOG.info("--loop: restarting after 1s pause")
        await asyncio.sleep(1.0)
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Real-time WAV replay into ml-engine ingest WS")
    p.add_argument("--wav", required=True, help="Path to 16-bit PCM WAV (mono or stereo)")
    p.add_argument("--session", default=None, help="Session id (also embedded in --ws)")
    p.add_argument("--ws", default=None, help="Full ws://…/ingest/{sessionId} URL")
    p.add_argument(
        "--ws-base",
        default="ws://127.0.0.1:8000/ingest",
        help="Base ingest URL when --ws is omitted",
    )
    p.add_argument(
        "--profile",
        default=None,
        help="On-the-fly codec degrade (P4.3 names: pcm_mulaw_8k, pcm_alaw_8k, …)",
    )
    p.add_argument(
        "--channel",
        default="PSTN_NARROWBAND",
        help="Channel profile advertised in IngestHello",
    )
    p.add_argument("--loop", action="store_true", help="Loop forever for rehearsal")
    p.add_argument(
        "--fast",
        action="store_true",
        help="Disable real-time pacing (NOT for demos — gauge will jump)",
    )
    p.add_argument(
        "--generate-placeholder",
        action="store_true",
        help="Synthesise a speech-like WAV at --wav before streaming",
    )
    p.add_argument("--duration", type=float, default=30.0, help="Placeholder duration seconds")
    return p


def main(argv: Optional[list[str]] = None) -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    args = build_parser().parse_args(argv)
    # Resolve relative paths from repo root when launched from elsewhere
    wav = Path(args.wav)
    if not wav.is_absolute() and not wav.exists():
        candidate = _repo_root() / wav
        if candidate.exists() or args.generate_placeholder:
            args.wav = str(candidate)
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        LOG.info("Interrupted")
        return 130


if __name__ == "__main__":
    sys.exit(main())
