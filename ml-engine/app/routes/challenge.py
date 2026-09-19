"""Challenge-response liveness helpers on the Inference Plane.

Arms a per-session watcher: after DISPLAYED, detect first VAD speech onset, notify Java,
capture a short response window, ASR + speaker-embed vs call baseline, POST evidence.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Optional

import httpx
import numpy as np
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.config import settings
from app.modules import asr as asr_mod
from app.modules import speaker as speaker_mod
from app.session import PipelineSession, registry
from app.vad import is_speech, speech_ratio

logger = logging.getLogger("sentinelvoice.ml.challenge")

router = APIRouter(tags=["challenge"])


class ArmRequest(BaseModel):
    nonce: str = Field(..., min_length=1)
    phrase: str = Field(..., min_length=1)
    captureMs: int = Field(default=3000, ge=500, le=10_000)


class DisplayedRequest(BaseModel):
    nonce: str = Field(..., min_length=1)


@dataclass
class ChallengeWatch:
    nonce: str
    phrase: str
    capture_ms: int
    displayed: bool = False
    onset_sent: bool = False
    evidence_sent: bool = False
    task: Optional[asyncio.Task[None]] = None
    baseline_embedding: Optional[np.ndarray] = field(default=None)


_watches: dict[str, ChallengeWatch] = {}


def _java_base() -> str:
    return (settings.java_decision_http or "http://127.0.0.1:8081").rstrip("/")


async def _post_java(path: str, payload: dict[str, Any]) -> None:
    url = _java_base().rstrip("/") + path
    try:
        async with httpx.AsyncClient(timeout=2.0) as client:
            await client.post(url, json=payload)
    except Exception as exc:
        logger.warning("challenge_java_post_failed path=%s err=%s", path, type(exc).__name__)


def _baseline_embed(session: PipelineSession) -> Optional[np.ndarray]:
    window_s = min(4.0, session.ring_buffer.capacity / float(session.ring_buffer.sample_rate))
    audio = session.ring_buffer.read_window(window_s)
    if audio.size < session.ring_buffer.sample_rate:
        return None
    if not speaker_mod.is_ready():
        speaker_mod.warmup()
    emb, _reason = speaker_mod.embed(audio, session.ring_buffer.sample_rate)
    return emb


async def _watch_loop(session_id: str, watch: ChallengeWatch) -> None:
    """Poll VAD until speech onset after display, then capture + evidence."""
    session = registry.get(session_id)
    if session is None:
        return

    # Wait until displayed.
    for _ in range(200):
        if watch.displayed or watch.evidence_sent:
            break
        await asyncio.sleep(0.05)
    if not watch.displayed or watch.evidence_sent:
        return

    sr = session.ring_buffer.sample_rate
    frame = max(1, int(sr * 0.03))
    # Require a brief quiet stretch then first speech.
    quiet_needed = 3
    quiet = 0
    onset_index = None
    deadline = time.monotonic() + 15.0

    while time.monotonic() < deadline and not watch.onset_sent:
        session = registry.get(session_id)
        if session is None:
            return
        buf = session.ring_buffer.read_window(0.5)
        if buf.size < frame:
            await asyncio.sleep(0.03)
            continue
        tail = buf[-frame:]
        speaking = bool(is_speech(tail)) or speech_ratio(buf[-sr // 2 :] if buf.size >= sr // 2 else buf) > 0.35
        if not speaking:
            quiet += 1
        elif quiet >= quiet_needed:
            onset_index = session.ring_buffer.total_samples_written
            watch.onset_sent = True
            await _post_java(
                "/api/v1/challenge/speech-onset",
                {
                    "sessionId": session_id,
                    "nonce": watch.nonce,
                    "mlMonotonicNanos": time.monotonic_ns(),
                },
            )
            break
        else:
            quiet = 0
        await asyncio.sleep(0.03)

    if not watch.onset_sent or watch.evidence_sent:
        return

    # Capture response window after onset.
    capture_s = watch.capture_ms / 1000.0
    await asyncio.sleep(capture_s)
    session = registry.get(session_id)
    if session is None:
        return

    audio = session.ring_buffer.read_window(capture_s + 0.5)
    if audio.size == 0:
        return

    # ASR (redacted delta path — we only need content tokens for matching).
    state = asr_mod.AsrSessionState(session_id=f"challenge-{session_id}")
    with_asr = asr_mod.transcribe_window(audio, sr, state)
    transcript = (with_asr.delta_redacted or with_asr.redacted_snippet or "").strip()

    if not speaker_mod.is_ready():
        speaker_mod.warmup()
    resp_emb, _ = speaker_mod.embed(audio, sr)
    cosine = 0.0
    if resp_emb is not None and watch.baseline_embedding is not None:
        a = watch.baseline_embedding.astype(np.float64)
        b = resp_emb.astype(np.float64)
        denom = (np.linalg.norm(a) * np.linalg.norm(b)) + 1e-12
        cosine = float(np.dot(a, b) / denom)
        cosine = max(0.0, min(1.0, (cosine + 1.0) / 2.0)) if cosine < 0 else float(cosine)
        # ECAPA cosines are typically already in a useful range; clamp to [0,1] for contract.
        cosine = max(0.0, min(1.0, cosine))

    watch.evidence_sent = True
    await _post_java(
        f"/api/v1/challenge/{watch.nonce}/evidence",
        {
            "transcript": transcript,
            "acousticCosine": cosine,
            "expectedPhrase": watch.phrase,
        },
    )
    logger.info(
        "challenge_evidence_sent session_id=%s nonce=%s overlap_ready transcript_chars=%d cosine=%.3f",
        session_id,
        watch.nonce,
        len(transcript),
        cosine,
    )


@router.post("/session/{sid}/challenge/arm")
async def arm_challenge(sid: str, body: ArmRequest) -> dict[str, Any]:
    session = registry.get(sid)
    if session is None:
        raise HTTPException(status_code=404, detail="session_not_found")
    prev = _watches.get(sid)
    if prev and prev.task and not prev.task.done():
        prev.task.cancel()
    watch = ChallengeWatch(
        nonce=body.nonce,
        phrase=body.phrase,
        capture_ms=body.captureMs,
        baseline_embedding=_baseline_embed(session),
    )
    watch.task = asyncio.create_task(_watch_loop(sid, watch))
    _watches[sid] = watch
    return {"status": "armed", "nonce": body.nonce}


@router.post("/session/{sid}/challenge/displayed")
async def challenge_displayed(sid: str, body: DisplayedRequest) -> dict[str, Any]:
    watch = _watches.get(sid)
    if watch is None or watch.nonce != body.nonce:
        raise HTTPException(status_code=404, detail="challenge_not_armed")
    watch.displayed = True
    return {"status": "displayed", "nonce": body.nonce}
