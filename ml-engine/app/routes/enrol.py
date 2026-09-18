"""Voice Passport enrolment — ECAPA embed, dual channel profiles, zeroise buffers.

POST /enrol accepts JSON ``{audioRef}`` (fixture path or ``synthetic:…``) or multipart
``file`` WAV upload. Returns wideband + narrowband 192-d embeddings. Audio is held only
in memory and zeroised before return — never written to disk.
"""

from __future__ import annotations

import io
import logging
import wave
from pathlib import Path
from typing import Any, Optional

import numpy as np
from fastapi import APIRouter, File, HTTPException, UploadFile
from pydantic import BaseModel, Field

from app.modules import speaker as speaker_mod
from app.types import ChannelProfile

logger = logging.getLogger("sentinelvoice.ml.enrol")

router = APIRouter(tags=["enrol"])

_ML_ROOT = Path(__file__).resolve().parents[2]
_FIXTURE_DIRS = (
    _ML_ROOT / "fixtures",
    _ML_ROOT / "testdata",
    _ML_ROOT.parent / "docs" / "contracts" / "fixtures",
)


class EnrolJsonRequest(BaseModel):
    audioRef: str = Field(..., min_length=1)


def _read_wav_bytes(data: bytes) -> tuple[np.ndarray, int]:
    with wave.open(io.BytesIO(data), "rb") as w:
        sr = w.getframerate()
        nch = w.getnchannels()
        sw = w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if sw != 2:
        raise HTTPException(status_code=400, detail=f"unsupported_sample_width:{sw}")
    pcm = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    if nch > 1:
        pcm = pcm.reshape(-1, nch).mean(axis=1)
    return pcm.copy(), sr


def _synthetic_enrolment_audio(seconds: float = 3.0, sr: int = 16000) -> tuple[np.ndarray, int]:
    t = np.linspace(0.0, seconds, int(sr * seconds), endpoint=False, dtype=np.float32)
    audio = (0.25 * np.sin(2 * np.pi * 180.0 * t) + 0.15 * np.sin(2 * np.pi * 240.0 * t)).astype(
        np.float32
    )
    audio += (0.02 * np.random.default_rng(42).standard_normal(audio.size)).astype(np.float32)
    return audio, sr


def _resolve_audio_ref(audio_ref: str) -> tuple[np.ndarray, int]:
    ref = audio_ref.strip()
    if ref.startswith("synthetic:"):
        return _synthetic_enrolment_audio()
    path = Path(ref)
    if not path.is_file():
        for root in _FIXTURE_DIRS:
            candidate = root / ref
            if candidate.is_file():
                path = candidate
                break
    if not path.is_file():
        logger.warning("audio_ref_missing ref=%s — using synthetic enrolment audio", ref)
        return _synthetic_enrolment_audio()
    return _read_wav_bytes(path.read_bytes())


def _degrade_narrowband(audio: np.ndarray, sr: int) -> tuple[np.ndarray, int]:
    """In-memory 8 kHz band-limit + upsample (G.711-like). No disk I/O."""
    target_sr = 8000
    n_out = max(1, int(round(audio.size * target_sr / float(sr))))
    x = np.linspace(0.0, 1.0, audio.size, dtype=np.float64)
    x8 = np.linspace(0.0, 1.0, n_out, dtype=np.float64)
    nb = np.interp(x8, x, audio.astype(np.float64)).astype(np.float32)
    nb = np.tanh(nb * 1.5).astype(np.float32) * 0.9
    n16 = max(1, int(round(nb.size * 16000 / float(target_sr))))
    x16 = np.linspace(0.0, 1.0, n16, dtype=np.float64)
    x_nb = np.linspace(0.0, 1.0, nb.size, dtype=np.float64)
    wide = np.interp(x16, x_nb, nb.astype(np.float64)).astype(np.float32)
    return wide, 16000


def _zeroise(*arrays: np.ndarray) -> None:
    for arr in arrays:
        if arr is not None and isinstance(arr, np.ndarray):
            arr.fill(0)


def _embed_dual(audio: np.ndarray, sr: int) -> dict[str, Any]:
    if not speaker_mod.is_ready():
        speaker_mod.warmup()

    wb_emb, wb_reason = speaker_mod.embed(audio, sr)
    if wb_emb is None:
        raise HTTPException(status_code=422, detail=f"wideband_embed_failed:{wb_reason}")

    nb_audio, nb_sr = _degrade_narrowband(audio, sr)
    nb_emb, nb_reason = speaker_mod.embed(nb_audio, nb_sr)
    _zeroise(nb_audio)
    if nb_emb is None:
        raise HTTPException(status_code=422, detail=f"narrowband_embed_failed:{nb_reason}")

    return {
        "modelId": speaker_mod.MODEL_SOURCE,
        "dim": speaker_mod.EMBED_DIM,
        "embeddings": {
            ChannelProfile.WEBRTC_WIDEBAND.value: wb_emb.astype(np.float64).tolist(),
            ChannelProfile.VOIP_WIDEBAND.value: wb_emb.astype(np.float64).tolist(),
            ChannelProfile.PSTN_NARROWBAND.value: nb_emb.astype(np.float64).tolist(),
        },
    }


@router.post("/enrol")
def enrol_json(request: EnrolJsonRequest) -> dict[str, Any]:
    """JSON enrolment used by the Java Decision Plane (audioRef only — no PCM proxy)."""
    audio: Optional[np.ndarray] = None
    try:
        audio, sr = _resolve_audio_ref(request.audioRef)
        result = _embed_dual(audio, sr)
        logger.info("enrol_ok model=%s profiles=%s", result["modelId"], sorted(result["embeddings"]))
        return result
    finally:
        if audio is not None:
            _zeroise(audio)


@router.post("/enrol/upload")
async def enrol_upload(file: UploadFile = File(...)) -> dict[str, Any]:
    """Multipart WAV upload path for local tooling. Buffer zeroised; never written to disk."""
    audio: Optional[np.ndarray] = None
    try:
        data = await file.read()
        audio, sr = _read_wav_bytes(data)
        del data
        result = _embed_dual(audio, sr)
        logger.info("enrol_upload_ok model=%s", result["modelId"])
        return result
    finally:
        if audio is not None:
            _zeroise(audio)
