from __future__ import annotations

import asyncio
import logging
import time
from contextlib import asynccontextmanager, suppress
from typing import Any

import numpy as np
from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect

from app import __version__
from app.config import settings
from app.emitter import FeatureEmitter
from app.normaliser import normalise
from app.routes.enrol import router as enrol_router
from app.routes.challenge import router as challenge_router
from app.scheduler import SessionScheduler
from app.session import registry
from app.slow_path import SlowPathRunner
from app.types import ChannelProfile, IngestHello
from app.vad import is_speech

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("sentinelvoice.ml")

emitter = FeatureEmitter()
scheduler = SessionScheduler(registry, emitter)
slow_path = SlowPathRunner(registry)
_emitter_task: asyncio.Task[None] | None = None


@asynccontextmanager
async def lifespan(_app: FastAPI):
    global _emitter_task
    # ECAPA-TDNN singleton — load once; never per window (Context §10.5).
    try:
        from app.modules import speaker as speaker_mod

        info = speaker_mod.warmup()
        logger.info(
            "speaker_warmup ready=%s warmup_ms=%s embed_latency_ms=%s stride=%s",
            info.get("ready"),
            info.get("warmup_ms"),
            info.get("embed_latency_ms"),
            info.get("window_stride"),
        )
    except Exception:
        logger.exception("speaker_warmup_failed — speaker features unavailable until fixed")

    # Tier-1 anti-spoof LCNN + Platt calibration (Context §10.1).
    try:
        from app.modules import antispoof as antispoof_mod

        ainfo = antispoof_mod.warmup()
        logger.info(
            "antispoof_warmup ready=%s tier=%s latency_ms=%s within_budget=%s",
            ainfo.get("ready"),
            ainfo.get("tier"),
            ainfo.get("infer_latency_ms"),
            ainfo.get("within_budget"),
        )
    except Exception:
        logger.exception("antispoof_warmup_failed — spoofProbability unavailable until trained")

    # Slow-path ASR (faster-whisper) — load once; log size/device (Context §7.2).
    if settings.asr_enabled:
        try:
            from app.modules import asr as asr_mod

            winfo = asr_mod.warmup()
            logger.info(
                "asr_warmup ready=%s size=%s device=%s compute=%s warmup_ms=%s",
                winfo.get("ready"),
                winfo.get("model_size"),
                winfo.get("device"),
                winfo.get("compute_type"),
                winfo.get("warmup_ms"),
            )
        except Exception:
            logger.exception("asr_warmup_failed — linguistic ASR unavailable until fixed")

    try:
        from app.modules import intent as intent_mod

        iinfo = intent_mod.warmup()
        logger.info(
            "intent_warmup lexicon_entries=%s semantic_ready=%s secrecy_threshold=%s",
            iinfo.get("lexicon_entries"),
            iinfo.get("semantic_ready"),
            iinfo.get("secrecy_threshold"),
        )
    except Exception:
        logger.exception("intent_warmup_failed — lexicon/semantic intent unavailable")

    if settings.emit_enabled:
        _emitter_task = asyncio.create_task(emitter.run(), name="feature-emitter")
        logger.info("lifespan_start emit_enabled=true url=%s", emitter.url)
    else:
        logger.info("lifespan_start emit_enabled=false")
    try:
        yield
    finally:
        await slow_path.stop_all()
        await scheduler.stop_all()
        await emitter.stop()
        if _emitter_task is not None:
            _emitter_task.cancel()
            with suppress(asyncio.CancelledError):
                await _emitter_task
            _emitter_task = None


app = FastAPI(title="SentinelVoice Inference Plane", version=__version__, lifespan=lifespan)
app.include_router(enrol_router)
app.include_router(challenge_router)


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "version": settings.version,
        "active_sessions": registry.active_count(),
        "emitter_connected": emitter.connected,
        "dropped_frames": emitter.dropped_frames,
        "sent_frames": emitter.sent_frames,
    }


@app.get("/diagnostics/{sid}")
def diagnostics(sid: str) -> dict[str, Any]:
    """Per-module availability, last error, and rolling p50/p95 latency."""
    session = registry.get(sid)
    if session is None:
        raise HTTPException(status_code=404, detail="session_not_found")
    out = session.fast_path.diagnostics()
    out["slowPath"] = {
        "latencyMs": session.slow_path_latency_ms,
        "linguisticAvailable": bool(
            (session.slow_path_linguistic or {}).get("available")
        ),
        "language": (session.slow_path_linguistic or {}).get("language"),
        "codeSwitchDetected": session.asr_state.code_switch_detected,
        "redactedDelta": (session.slow_path_linguistic or {}).get("redactedDelta"),
        "redactedSnippet": (session.slow_path_linguistic or {}).get("redactedSnippet"),
        "asr": {
            "modelSize": settings.asr_model_size,
            "enabled": settings.asr_enabled,
        },
    }
    return out


@app.post("/session/{sid}/open")
async def open_session(
    sid: str, profile: ChannelProfile = ChannelProfile.WEBRTC_WIDEBAND
) -> dict[str, Any]:
    session = registry.create(sid, profile=profile)
    await scheduler.start(sid)
    await slow_path.start(sid)
    logger.info("session_open session_id=%s profile=%s", sid, session.profile.value)
    return {"status": "open", "sessionId": sid, "profile": session.profile.value}


@app.post("/session/{sid}/close")
async def close_session(sid: str) -> dict[str, Any]:
    await slow_path.stop(sid)
    await scheduler.stop(sid)
    closed = registry.close(sid)
    if not closed:
        raise HTTPException(status_code=404, detail="session_not_found")
    logger.info("session_close session_id=%s", sid)
    return {"status": "closed", "sessionId": sid}


@app.post("/session/{sid}/pcm")
async def push_pcm(sid: str, request: Request) -> dict[str, Any]:
    """
    Append float32 LE mono samples (@ settings.sample_rate, typically 16 kHz)
    to the session ring buffer. Used by gateway/asterisk_bridge.py (P7.2).
    """
    session = registry.get(sid)
    if session is None:
        raise HTTPException(status_code=404, detail="session_not_found")
    raw = await request.body()
    if not raw:
        return {"status": "ok", "sessionId": sid, "samples": 0}
    if len(raw) % 4:
        raw = raw[: len(raw) - (len(raw) % 4)]
    pcm = np.frombuffer(raw, dtype="<f4").astype(np.float32, copy=True)
    session.ring_buffer.write(pcm)
    session.seq += 1
    if is_speech(pcm):
        session.cumulative_speech_ms += int(
            round(1000.0 * pcm.size / settings.sample_rate)
        )
    return {
        "status": "ok",
        "sessionId": sid,
        "samples": int(pcm.size),
        "seq": session.seq,
    }


@app.websocket("/ingest/{sid}")
async def ingest(websocket: WebSocket, sid: str) -> None:
    await websocket.accept()
    session = registry.get(sid)
    if session is None:
        session = registry.create(sid)
        logger.info("session_open_via_ws session_id=%s", sid)
    await scheduler.start(sid)
    await slow_path.start(sid)

    hello: IngestHello | None = None
    frames = 0
    try:
        first = await websocket.receive()
        if first.get("type") == "websocket.disconnect":
            return
        text = first.get("text")
        if not text:
            await websocket.close(code=1003)
            logger.info("ingest_rejected session_id=%s reason=missing_hello", sid)
            return
        hello = IngestHello.model_validate_json(text)
        if hello.channel is not None:
            session.profile = hello.channel
        logger.info(
            "ingest_hello session_id=%s encoding=%s sample_rate=%s channels=%s",
            sid,
            hello.encoding,
            hello.sampleRate,
            hello.channels,
        )

        while True:
            message = await websocket.receive()
            if message.get("type") == "websocket.disconnect":
                break
            payload = message.get("bytes")
            if payload is None:
                continue
            nbytes = len(payload)
            pcm, profile = normalise(
                payload,
                hello.encoding,
                hello.sampleRate,
                hello.channels,
                profile=hello.channel,
                source=hello.source,
            )
            session.profile = profile
            session.ring_buffer.write(pcm)
            session.seq += 1
            frames += 1
            if is_speech(pcm):
                session.cumulative_speech_ms += int(round(1000.0 * pcm.size / settings.sample_rate))
            logger.info(
                "ingest_frame session_id=%s seq=%s nbytes=%s samples=%s",
                sid,
                session.seq,
                nbytes,
                int(pcm.size),
            )
            if frames % settings.ack_every_frames == 0:
                await websocket.send_json(
                    {
                        "type": "ack",
                        "seq": session.seq,
                        "nbytes": nbytes,
                        "total_samples": session.ring_buffer.total_samples_written,
                        "received_at_ms": int(time.time() * 1000),
                    }
                )
    except WebSocketDisconnect:
        logger.info("ingest_disconnect session_id=%s frames=%s", sid, frames)
    except Exception:
        logger.exception("ingest_error session_id=%s frames=%s", sid, frames)
        raise
