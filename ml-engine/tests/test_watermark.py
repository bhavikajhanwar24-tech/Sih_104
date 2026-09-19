"""P13.3 AudioSeal watermark scanner — attribution only (Context §10.7)."""

from __future__ import annotations

import os
import re
from pathlib import Path

import numpy as np
import pytest
import yaml

from app.modules import watermark
from app.modules.watermark_providers.audioseal import AudioSealProvider
from app.modules.watermark_providers.stub_synthid import (
    UNAVAILABLE_REASON,
    SynthIdStubProvider,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND_FUSION = REPO_ROOT / "backend" / "src" / "main" / "java" / "com" / "sentinelvoice" / "fusion"
APPLICATION_YML = REPO_ROOT / "backend" / "src" / "main" / "resources" / "application.yml"

# Require enough speech for a stable AudioSeal hit (generator + detector @ 16 kHz).
_SR = 16_000
_N_SAMPLES = 32_000  # 2.0 s


def _disable_torch_compile() -> None:
    os.environ.setdefault("TORCH_COMPILE_DISABLE", "1")
    try:
        import torch

        torch._dynamo.config.disable = True  # type: ignore[attr-defined]
    except Exception:  # noqa: BLE001
        pass


@pytest.fixture(scope="module")
def clean_and_watermarked() -> tuple[np.ndarray, np.ndarray]:
    """Return (clean, watermarked) mono float32 clips at 16 kHz."""
    pytest.importorskip("audioseal")
    _disable_torch_compile()
    import torch
    from audioseal import AudioSeal

    torch.manual_seed(7)
    clean = (torch.randn(1, 1, _N_SAMPLES) * 0.08).float()
    gen = AudioSeal.load_generator("audioseal_wm_16bits")
    gen.eval()
    with torch.no_grad():
        watermarked = gen(clean, sample_rate=_SR)
    return (
        clean.squeeze().cpu().numpy().astype(np.float32),
        watermarked.squeeze().cpu().numpy().astype(np.float32),
    )


def test_attribution_only_flag_is_true() -> None:
    assert watermark.ATTRIBUTION_ONLY is True


def test_synthid_stub_returns_honest_unavailable() -> None:
    audio = np.zeros(1024, dtype=np.float32)
    out = SynthIdStubProvider().detect(audio, 16_000)
    assert out["available"] is False
    assert out["reason"] == UNAVAILABLE_REASON
    assert out["reason"] == "SynthID detection requires provider-side tooling"
    assert out["detected"] is False
    assert out["provider"] == "synthid"


def test_audioseal_detects_watermarked_not_clean(
    clean_and_watermarked: tuple[np.ndarray, np.ndarray],
) -> None:
    clean, marked = clean_and_watermarked
    provider = AudioSealProvider()

    clean_out = provider.detect(clean, _SR)
    assert clean_out["available"] is True
    assert clean_out["detected"] is False
    assert clean_out["provider"] == "audioseal"
    assert float(clean_out["confidence"]) < 0.5
    assert clean_out["spans"] == []

    marked_out = provider.detect(marked, _SR)
    assert marked_out["available"] is True
    assert marked_out["detected"] is True
    assert marked_out["provider"] == "audioseal"
    assert float(marked_out["confidence"]) >= 0.5
    assert isinstance(marked_out["spans"], list)
    assert len(marked_out["spans"]) >= 1
    span = marked_out["spans"][0]
    assert "start_s" in span and "end_s" in span
    assert span["end_s"] > span["start_s"]


def test_extract_uses_audioseal_and_tags_reason(
    clean_and_watermarked: tuple[np.ndarray, np.ndarray],
) -> None:
    clean, marked = clean_and_watermarked

    clean_block = watermark.extract(clean, _SR)
    assert clean_block["available"] is True
    assert clean_block["detected"] is False
    assert clean_block["attribution_only"] is True
    assert clean_block.get("reason_code") is None

    marked_block = watermark.extract(marked, _SR)
    assert marked_block["available"] is True
    assert marked_block["detected"] is True
    assert marked_block["provider"] == "audioseal"
    assert marked_block["reason_code"] == watermark.WATERMARK_DETECTED_REASON
    assert marked_block["attribution_only"] is True
    assert len(marked_block["spans"]) >= 1


def test_architecture_watermark_cannot_influence_risk() -> None:
    """Code-level guarantee: watermark is attribution-only, never a fusion weight.

    Reasoning: no real attacker uses a watermarking TTS, so absence of a
    watermark carries zero information. Wiring it into the score would make
    unwatermarked audio look safer than it is (Context §10.7 / §16.3).
    """
    assert watermark.ATTRIBUTION_ONLY is True

    # 1) EvidenceFamily enum has exactly the six fusion families — no watermark.
    evidence_src = (BACKEND_FUSION / "EvidenceFamily.java").read_text(encoding="utf-8")
    enum_names = re.findall(
        r"^\s*(VOICE|CHANNEL|PROSODY|LINGUISTIC|TRANSACTION|RELATIONSHIP|WATERMARK)\s*\(",
        evidence_src,
        flags=re.MULTILINE,
    )
    assert "WATERMARK" not in enum_names
    assert set(enum_names) == {
        "VOICE",
        "CHANNEL",
        "PROSODY",
        "LINGUISTIC",
        "TRANSACTION",
        "RELATIONSHIP",
    }

    # 2) application.yml fusion weights omit watermark for both profiles.
    yml = yaml.safe_load(APPLICATION_YML.read_text(encoding="utf-8"))
    weights = yml["sentinelvoice"]["fusion"]["weights"]
    for profile_name in ("wideband", "narrowband"):
        keys = set(weights[profile_name].keys())
        assert "watermark" not in keys, f"{profile_name} weights must not include watermark"
        assert keys == {
            "voice",
            "channel",
            "prosody",
            "linguistic",
            "transaction",
            "relationship",
        }

    thresholds = yml["sentinelvoice"]["fusion"]["family-thresholds"]
    assert "watermark" not in thresholds

    # 3) FusionEngineService.extractFamilies never reads frame.watermark().
    fusion_src = (BACKEND_FUSION / "FusionEngineService.java").read_text(encoding="utf-8")
    # Isolate extractFamilies body roughly between its signature and the next method.
    m = re.search(
        r"private Map<EvidenceFamily, Extracted> extractFamilies\(FusionContext context\)\s*\{(.*?)^\s{4}\w",
        fusion_src,
        flags=re.DOTALL | re.MULTILINE,
    )
    assert m is not None, "could not locate extractFamilies in FusionEngineService"
    body = m.group(1)
    assert "watermark" not in body.lower(), (
        "extractFamilies must not reference watermark — attribution must stay "
        "out of the risk score"
    )

    # 4) ReasonCode maps WATERMARK_DETECTED to INFO (enrichment), not a score path.
    reason_src = (BACKEND_FUSION / "ReasonCode.java").read_text(encoding="utf-8")
    wm_block = re.search(
        r"WATERMARK_DETECTED\s*\(\s*Severity\.(\w+)",
        reason_src,
    )
    assert wm_block is not None
    assert wm_block.group(1) == "INFO"
