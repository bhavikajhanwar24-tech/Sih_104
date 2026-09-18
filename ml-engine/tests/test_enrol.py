"""Unit tests for ml-engine /enrol — dual-channel embed, zeroise, no disk write."""

from __future__ import annotations

import io
import wave
from pathlib import Path
from unittest.mock import MagicMock, patch

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.routes import enrol as enrol_mod
from app.types import ChannelProfile

SR = 16000


def _wav_bytes(seconds: float = 1.0, sr: int = SR) -> bytes:
    n = int(sr * seconds)
    t = np.arange(n, dtype=np.float64) / sr
    audio = (0.3 * np.sin(2 * np.pi * 180 * t)).astype(np.float32)
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype(np.int16)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())
    return buf.getvalue()


def _fake_embed(audio: np.ndarray, sr: int):
    # Deterministic 192-d unit vector from audio mean — proves dual-channel path runs.
    rng = np.random.default_rng(abs(hash(float(np.mean(audio)))) % (2**32))
    vec = rng.standard_normal(192).astype(np.float32)
    vec /= np.linalg.norm(vec) + 1e-9
    return vec, None


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def test_enrol_json_synthetic_returns_wb_and_nb(client: TestClient) -> None:
    with (
        patch.object(enrol_mod.speaker_mod, "is_ready", return_value=True),
        patch.object(enrol_mod.speaker_mod, "embed", side_effect=_fake_embed),
        patch.object(enrol_mod.speaker_mod, "MODEL_SOURCE", "test-ecapa"),
        patch.object(enrol_mod.speaker_mod, "EMBED_DIM", 192),
    ):
        resp = client.post("/enrol", json={"audioRef": "synthetic:unit"})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["dim"] == 192
    assert body["modelId"] == "test-ecapa"
    emb = body["embeddings"]
    assert ChannelProfile.WEBRTC_WIDEBAND.value in emb
    assert ChannelProfile.PSTN_NARROWBAND.value in emb
    assert len(emb[ChannelProfile.WEBRTC_WIDEBAND.value]) == 192
    assert len(emb[ChannelProfile.PSTN_NARROWBAND.value]) == 192
    # Codec-degraded NB must differ from WB for non-trivial audio.
    assert emb[ChannelProfile.WEBRTC_WIDEBAND.value] != emb[ChannelProfile.PSTN_NARROWBAND.value]


def test_enrol_multipart_on_enrol_path(client: TestClient) -> None:
    with (
        patch.object(enrol_mod.speaker_mod, "is_ready", return_value=True),
        patch.object(enrol_mod.speaker_mod, "embed", side_effect=_fake_embed),
        patch.object(enrol_mod.speaker_mod, "MODEL_SOURCE", "test-ecapa"),
        patch.object(enrol_mod.speaker_mod, "EMBED_DIM", 192),
    ):
        resp = client.post(
            "/enrol",
            files={"file": ("enrol.wav", _wav_bytes(), "audio/wav")},
        )
    assert resp.status_code == 200, resp.text
    assert ChannelProfile.WEBRTC_WIDEBAND.value in resp.json()["embeddings"]


def test_enrol_upload_alias(client: TestClient) -> None:
    with (
        patch.object(enrol_mod.speaker_mod, "is_ready", return_value=True),
        patch.object(enrol_mod.speaker_mod, "embed", side_effect=_fake_embed),
        patch.object(enrol_mod.speaker_mod, "MODEL_SOURCE", "test-ecapa"),
        patch.object(enrol_mod.speaker_mod, "EMBED_DIM", 192),
    ):
        resp = client.post(
            "/enrol/upload",
            files={"file": ("enrol.wav", _wav_bytes(), "audio/wav")},
        )
    assert resp.status_code == 200, resp.text


def test_enrol_zeroises_buffer_and_never_writes_upload() -> None:
    audio, sr = enrol_mod._synthetic_enrolment_audio()
    assert float(np.max(np.abs(audio))) > 0.0

    writes: list[str] = []
    real_open = Path.open

    def _guard_open(self: Path, *args, **kwargs):
        mode = args[0] if args else kwargs.get("mode", "r")
        if isinstance(mode, str) and any(c in mode for c in "wax+"):
            writes.append(str(self))
        return real_open(self, *args, **kwargs)

    with (
        patch.object(enrol_mod.speaker_mod, "is_ready", return_value=True),
        patch.object(enrol_mod.speaker_mod, "embed", side_effect=_fake_embed),
        patch.object(enrol_mod.speaker_mod, "MODEL_SOURCE", "test-ecapa"),
        patch.object(enrol_mod.speaker_mod, "EMBED_DIM", 192),
        patch.object(Path, "open", _guard_open),
    ):
        result = enrol_mod._enrol_from_audio(audio, sr)

    assert result["dim"] == 192
    assert float(np.max(np.abs(audio))) == 0.0  # zeroised in finally
    assert writes == [], f"enrol wrote to disk: {writes}"


def test_degrade_narrowband_is_in_memory_only() -> None:
    audio, sr = enrol_mod._synthetic_enrolment_audio()
    nb, nb_sr = enrol_mod._degrade_narrowband(audio, sr)
    assert nb_sr == 16000
    assert nb.size > 0
    assert not np.allclose(nb[: min(1000, nb.size)], audio[: min(1000, audio.size)])
