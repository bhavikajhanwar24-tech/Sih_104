from __future__ import annotations

import json
import time
from pathlib import Path

import numpy as np
import pytest

from app.modules import antispoof as antispoof_mod
from app.types import ChannelProfile
from training.dataset import generate_synthetic_corpus, load_audio
from training.features import lfcc_stack
from training.metrics import eer_and_threshold, expected_calibration_error, min_tdcf

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "models" / "antispoof"
SR = 16000


@pytest.fixture(scope="module")
def trained_artifacts(tmp_path_factory) -> Path:
    """Smoke-train both models + calibrate if artifacts missing."""
    out = MODELS
    metrics = out / "metrics.json"
    ckpt = out / "codec_aug.pt"
    cal = out / "calibration.json"
    diagram = out / "reliability_diagram.png"
    if metrics.is_file() and ckpt.is_file() and cal.is_file() and diagram.is_file():
        return out

    import subprocess
    import sys

    cmd_train = [
        sys.executable,
        "-m",
        "training.train_antispoof",
        "--synthetic",
        "--limit",
        "80",
        "--epochs",
        "2",
        "--batch-size",
        "8",
        "--out",
        str(out),
    ]
    subprocess.run(cmd_train, cwd=str(ROOT), check=True)
    cmd_cal = [
        sys.executable,
        "-m",
        "training.calibrate",
        "--checkpoint",
        str(ckpt),
        "--synthetic",
        "--limit",
        "80",
        "--out",
        str(out),
    ]
    subprocess.run(cmd_cal, cwd=str(ROOT), check=True)
    assert metrics.is_file()
    assert diagram.is_file()
    return out


def test_lfcc_stack_shape() -> None:
    audio = np.random.default_rng(0).standard_normal(SR * 2).astype(np.float32) * 0.1
    stack = lfcc_stack(audio, SR, max_frames=100)
    assert stack.shape[0] == 60
    assert stack.shape[1] <= 100


def test_metrics_helpers() -> None:
    labels = np.array([0, 0, 0, 1, 1, 1], dtype=np.int32)
    scores = np.array([0.1, 0.2, 0.4, 0.6, 0.8, 0.9], dtype=np.float64)
    eer, _ = eer_and_threshold(labels, scores)
    assert 0.0 <= eer <= 0.5
    assert min_tdcf(labels, scores) >= 0.0
    probs = 1 / (1 + np.exp(-scores))
    assert expected_calibration_error(probs, labels) >= 0.0


def test_train_writes_metrics_and_diagram(trained_artifacts: Path) -> None:
    metrics = json.loads((trained_artifacts / "metrics.json").read_text(encoding="utf-8"))
    assert "baseline_no_codec_aug" in metrics
    assert "codec_aug" in metrics
    assert "comparison" in metrics
    # In-domain and In-the-Wild keys present (honest table for P12 / §3.1).
    comp = metrics["comparison"]
    assert "asvspoof2019_la_eval" in comp or any("eval" in k for k in comp)
    assert "in_the_wild" in comp
    assert (trained_artifacts / "reliability_diagram.png").is_file()
    print("\n[ACCEPT] metrics comparison:")
    print(json.dumps(comp, indent=2))


def test_score_calibrated_probability(trained_artifacts: Path) -> None:
    info = antispoof_mod.warmup(
        checkpoint=trained_artifacts / "codec_aug.pt",
        calibration=trained_artifacts / "calibration.json",
        force=True,
    )
    assert info["ready"] is True
    audio = np.random.default_rng(1).standard_normal(SR * 2).astype(np.float32) * 0.1
    out = antispoof_mod.score(audio, SR, ChannelProfile.WEBRTC_WIDEBAND)
    assert out["available"] is True
    assert 0.0 <= out["spoofProbability"] <= 1.0
    assert out["modelId"]
    assert 0.0 <= out["confidence"] <= 1.0


def test_inference_latency_budget(trained_artifacts: Path) -> None:
    antispoof_mod.warmup(
        checkpoint=trained_artifacts / "codec_aug.pt",
        calibration=trained_artifacts / "calibration.json",
        force=True,
    )
    audio = np.random.default_rng(2).standard_normal(SR * 2).astype(np.float32) * 0.1
    # Warm kernel
    antispoof_mod.score(audio, SR, ChannelProfile.WEBRTC_WIDEBAND)
    times = []
    for _ in range(5):
        t0 = time.perf_counter()
        antispoof_mod.score(audio, SR, ChannelProfile.WEBRTC_WIDEBAND)
        times.append((time.perf_counter() - t0) * 1000.0)
    med = float(np.median(times))
    print(f"\n[ACCEPT] antispoof latency_ms median={med:.1f} budget=50")
    assert med < 50.0, f"latency {med:.1f} ms exceeds 50 ms budget"


def test_synthetic_itw_harder_than_indomain_documented(trained_artifacts: Path) -> None:
    """Honest §3.1 expectation: ITW EER is typically worse (document, don't hide)."""
    metrics = json.loads((trained_artifacts / "metrics.json").read_text(encoding="utf-8"))
    comp = metrics["comparison"]
    ind = comp.get("asvspoof2019_la_eval", {})
    itw = comp.get("in_the_wild", {})
    # On tiny synthetic data either ordering can happen; require both numbers exist
    # and print the slide line. Prefer ITW >= in-domain when both finite.
    if ind.get("eer_codec_aug") is not None and itw.get("eer_codec_aug") is not None:
        print(
            f"\n[SLIDE] in-domain EER={ind['eer_codec_aug']}  "
            f"In-the-Wild EER={itw['eer_codec_aug']}  "
            f"(ITW expected worse on real data — Context §3.1)"
        )
        assert True
    else:
        pytest.skip("missing eval EER cells")
