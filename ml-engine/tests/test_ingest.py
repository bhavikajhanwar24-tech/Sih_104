from __future__ import annotations

import json

import numpy as np
from fastapi.testclient import TestClient

from app.main import app
from app.session import registry


def test_health() -> None:
    client = TestClient(app)
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert "version" in body
    assert "active_sessions" in body


def test_open_stream_100_frames_and_close() -> None:
    sid = "p21-ingest-100"
    client = TestClient(app)
    opened = client.post(f"/session/{sid}/open")
    assert opened.status_code == 200
    assert opened.json()["status"] == "open"

    frame = np.zeros(8000, dtype="<i2").tobytes()
    hello = {
        "sessionId": sid,
        "sampleRate": 16000,
        "encoding": "pcm_s16le",
        "channels": 1,
        "channel": "WEBRTC_WIDEBAND",
        "source": "browser",
    }
    acks = []
    with client.websocket_connect(f"/ingest/{sid}") as ws:
        ws.send_text(json.dumps(hello))
        for i in range(100):
            ws.send_bytes(frame)
            if (i + 1) % 10 == 0:
                acks.append(ws.receive_json())
    assert len(acks) == 10
    assert acks[-1]["type"] == "ack"
    assert acks[-1]["seq"] == 100

    session = registry.get(sid)
    assert session is not None
    assert session.seq == 100
    assert session.ring_buffer.total_samples_written == 8000 * 100

    closed = client.post(f"/session/{sid}/close")
    assert closed.status_code == 200
    assert closed.json()["status"] == "closed"
    assert registry.get(sid) is None
    missing = client.post(f"/session/{sid}/close")
    assert missing.status_code == 404
