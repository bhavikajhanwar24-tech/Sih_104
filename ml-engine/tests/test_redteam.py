"""Acceptance tests for red-team robustness API (Context §5.1 A7 / §16.2)."""

from __future__ import annotations

import base64

import numpy as np
from fastapi.testclient import TestClient

from app.main import app
from app.modules import adversarial as adv
from app.routes import redteam as redteam_routes


client = TestClient(app)


def test_capabilities_deny_clone_generation():
    r = client.get("/redteam/capabilities")
    assert r.status_code == 200
    body = r.json()
    assert body["clone_generation"] is False
    assert "voice_clone_generation" in body["forbidden"]
    assert "additive_noise" in body["perturbations"]


def test_noise_degrades_voice_contextual_held_flat():
    """Moving SNR 30→5 drops acoustic voice; contextual families stay flat."""
    audio = redteam_routes._synthetic_probe(16000, seconds=1.5)
    profile = redteam_routes.ChannelProfile.WEBRTC_WIDEBAND

    clean_cfg = adv.PerturbationConfig(enabled=True, noise_snr_db=30.0).clamp()
    noisy_cfg = adv.PerturbationConfig(enabled=True, noise_snr_db=5.0).clamp()

    clean_audio = adv.apply(audio, 16000, clean_cfg, rng=np.random.default_rng(0))
    noisy_audio = adv.apply(audio, 16000, noisy_cfg, rng=np.random.default_rng(0))

    clean = redteam_routes.score_acoustic_families(clean_audio, 16000, profile, cfg=clean_cfg)
    noisy = redteam_routes.score_acoustic_families(noisy_audio, 16000, profile, cfg=noisy_cfg)

    assert noisy["voice"] < clean["voice"]
    # Contextual are supplied by the sweep API, not recomputed from audio —
    # verify the sweep contract holds them flat.
    sid = "rt-acceptance-sweep"
    r = client.post(
        f"/redteam/{sid}/sweep",
        json={
            "axis": "noise_snr_db",
            "values": [30, 15, 5],
            "base": {"enabled": True, "noise_snr_db": 30},
            "contextual": {
                "linguistic": 0.62,
                "transaction": 0.58,
                "relationship": 0.55,
            },
            "export_png": True,
        },
    )
    assert r.status_code == 200
    body = r.json()
    assert body["clone_generation"] is False
    points = body["points"]
    assert len(points) == 3
    voices = [p["families"]["voice"] for p in points]
    assert voices[-1] < voices[0], "voice must drop as SNR worsens"
    for p in points:
        assert p["families"]["linguistic"] == 0.62
        assert p["families"]["transaction"] == 0.58
        assert p["families"]["relationship"] == 0.55
    assert body.get("pngBase64"), "sweep must produce exportable PNG"
    raw = base64.b64decode(body["pngBase64"])
    assert raw[:8] == b"\x89PNG\r\n\x1a\n"


def test_module_has_no_clone_apis():
    assert not hasattr(adv, "generate_clone")
    assert not hasattr(adv, "voice_convert")
    assert not hasattr(adv, "synthesize")
    assert not hasattr(adv, "rvc")
