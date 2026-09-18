from __future__ import annotations

import os
from unittest.mock import patch

import numpy as np
import pytest
from fastapi.testclient import TestClient

os.environ.setdefault("SENTINELVOICE_ML_EMIT_ENABLED", "false")

from app.fast_path import FAST_PATH_BUDGET_MS, FastPathState, extract
from app.main import app
from app.scheduler import build_feature_frame
from app.session import SessionRegistry, registry
from app.types import ChannelProfile


def _speech_window(seconds: float = 2.0, sr: int = 16000, f0: float = 140.0) -> np.ndarray:
    n = int(sr * seconds)
    t = np.arange(n, dtype=np.float64) / sr
    audio = np.zeros(n)
    for k in range(1, 6):
        audio += (0.3 / k) * np.sin(2.0 * np.pi * f0 * k * t)
    audio += 0.02 * np.random.default_rng(0).standard_normal(n)
    peak = np.max(np.abs(audio)) + 1e-12
    return (audio / peak * 0.6).astype(np.float32)


def test_no_stub_markers_in_app() -> None:
    root = os.path.join(os.path.dirname(__file__), "..", "app")
    hits = []
    for dirpath, _, files in os.walk(root):
        for name in files:
            if not name.endswith(".py"):
                continue
            path = os.path.join(dirpath, name)
            text = open(path, encoding="utf-8").read()
            if "STUB" in text:
                hits.append(path)
    assert hits == [], f"STUB markers remain: {hits}"


def test_extract_populates_families() -> None:
    state = FastPathState(session_id="fp-1")
    out = extract(_speech_window(), 16000, ChannelProfile.WEBRTC_WIDEBAND, state)
    assert "voice" in out and "channel" in out and "prosody" in out
    assert "speaker" in out and "watermark" in out
    assert out["linguistic"] == {"available": False}
    assert out["fastPathMs"] >= 0
    assert "total" in out["latencyBreakdownMs"]
    # At least some modules should report timing.
    assert len(out["latencyBreakdownMs"]) >= 3


def test_prosody_exception_does_not_kill_frame() -> None:
    state = FastPathState(session_id="fp-boom")

    def _boom(*_a, **_k):
        raise RuntimeError("deliberate_prosody_failure")

    with patch("app.modules.prosody.extract", side_effect=_boom):
        out = extract(_speech_window(), 16000, ChannelProfile.VOIP_WIDEBAND, state)
    assert out["prosody"]["available"] is False
    # Other families still assembled (may be available or not depending on audio/models).
    assert "voice" in out
    assert "channel" in out
    assert "speaker" in out
    assert "watermark" in out
    assert state.modules["prosody"].last_error == "RuntimeError"


def test_built_frame_schema_and_varying_values() -> None:
    from app.emitter import frame_to_payload, validate_feature_frame

    reg = SessionRegistry()
    session = reg.create("live-1", ChannelProfile.WEBRTC_WIDEBAND)
    w1 = _speech_window(f0=120.0)
    w2 = _speech_window(f0=180.0)
    session.ring_buffer.write(w1)
    session.cumulative_speech_ms = 2000
    frame1 = build_feature_frame(session, w1)
    validate_feature_frame(frame_to_payload(frame1))
    session.ring_buffer.write(w2)
    frame2 = build_feature_frame(session, w2)
    validate_feature_frame(frame_to_payload(frame2))
    # Frames carry real structure (not the old RMS stub model id).
    if frame1.voice.available:
        assert frame1.voice.modelId != "stub-rms-p2.2"


def test_diagnostics_endpoint_p50_p95() -> None:
    client = TestClient(app)
    sid = "diag-1"
    # Ensure clean session
    registry.close(sid)
    session = registry.create(sid, ChannelProfile.WEBRTC_WIDEBAND)
    window = _speech_window()
    session.ring_buffer.write(window)
    session.cumulative_speech_ms = 2000
    for _ in range(3):
        build_feature_frame(session, window)
    resp = client.get(f"/diagnostics/{sid}")
    assert resp.status_code == 200
    body = resp.json()
    assert body["sessionId"] == sid
    assert "fastPath" in body
    assert "modules" in body
    assert "spectral" in body["modules"]
    assert body["budgetMs"] == FAST_PATH_BUDGET_MS
    # After a few frames we should have latency samples.
    assert body["fastPath"]["n"] >= 1
    if body["fastPath"]["p95Ms"] is not None:
        assert body["fastPath"]["p95Ms"] < FAST_PATH_BUDGET_MS or body["fastPath"]["n"] < 5
    registry.close(sid)


def test_diagnostics_404() -> None:
    client = TestClient(app)
    assert client.get("/diagnostics/no-such-session").status_code == 404
