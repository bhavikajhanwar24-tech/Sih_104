"""Evaluation metrics for anti-spoofing (Context §15.2).

Higher score ⇒ more spoof-like (positive class = spoof / attack).
DO NOT tune operating thresholds on the evaluation set — report EER / min t-DCF / FPR@TPR.

min t-DCF follows the ASVspoof 2019 CM formulation
(Todisco et al., "ASVspoof 2019: Future Horizons in Spoofed and Fake Audio Detection",
Interspeech 2019; protocol defaults C_miss=1, C_fa=10, π_spoof=0.05).
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Optional, Sequence

import numpy as np
from numpy.typing import NDArray

# ASVspoof 2019 LA countermeasure prior / costs (official protocol defaults).
ASVSPOOF2019_C_MISS = 1.0
ASVSPOOF2019_C_FA = 10.0
ASVSPOOF2019_P_SPOOF = 0.05


def _as_arrays(
    labels: Sequence[int] | NDArray[np.integer],
    scores: Sequence[float] | NDArray[np.floating],
) -> tuple[NDArray[np.int32], NDArray[np.float64]]:
    y = np.asarray(labels, dtype=np.int32)
    s = np.asarray(scores, dtype=np.float64)
    if y.shape != s.shape:
        raise ValueError(f"labels/scores shape mismatch: {y.shape} vs {s.shape}")
    return y, s


def roc_curve(
    labels: Sequence[int] | NDArray[np.integer],
    scores: Sequence[float] | NDArray[np.floating],
) -> tuple[NDArray[np.float64], NDArray[np.float64], NDArray[np.float64]]:
    """Return FPR, TPR, thresholds (descending score order)."""
    y, s = _as_arrays(labels, scores)
    if y.size == 0 or np.unique(y).size < 2:
        return (
            np.array([0.0, 1.0]),
            np.array([0.0, 1.0]),
            np.array([np.inf, -np.inf]),
        )
    order = np.argsort(-s)
    y = y[order]
    s = s[order]
    n_pos = float(np.sum(y == 1))
    n_neg = float(np.sum(y == 0))
    tps = np.cumsum(y == 1)
    fps = np.cumsum(y == 0)
    # Deduplicate tied scores.
    distinct = np.r_[True, s[1:] != s[:-1]]
    tps = tps[distinct]
    fps = fps[distinct]
    thr = s[distinct]
    fpr = fps / max(n_neg, 1.0)
    tpr = tps / max(n_pos, 1.0)
    # Anchor (0,0) and (1,1).
    fpr = np.r_[0.0, fpr, 1.0]
    tpr = np.r_[0.0, tpr, 1.0]
    thr = np.r_[thr[0] + 1e-9 if thr.size else 1.0, thr, thr[-1] - 1e-9 if thr.size else 0.0]
    return fpr.astype(np.float64), tpr.astype(np.float64), thr.astype(np.float64)


def auc_score(labels: Sequence[int] | NDArray[np.integer], scores: Sequence[float] | NDArray[np.floating]) -> float:
    fpr, tpr, _ = roc_curve(labels, scores)
    trapz = getattr(np, "trapezoid", None) or getattr(np, "trapz")
    return float(trapz(tpr, fpr))


def eer_and_threshold(
    labels: Sequence[int] | NDArray[np.integer],
    scores: Sequence[float] | NDArray[np.floating],
) -> tuple[float, float]:
    """Equal Error Rate and the exact score threshold (FPR ≈ FNR).

    Uses ROC interpolation between bracketing points where FPR−FNR changes sign.
    """
    y, s = _as_arrays(labels, scores)
    if y.size == 0 or np.unique(y).size < 2:
        return 0.5, 0.0
    fpr, tpr, thr = roc_curve(y, s)
    fnr = 1.0 - tpr
    diff = fpr - fnr
    # Exact crossing
    for i in range(len(diff) - 1):
        if diff[i] == 0:
            return float(fpr[i]), float(thr[i])
        if diff[i] * diff[i + 1] < 0:
            # Linear interpolate in FPR/FNR space.
            w = abs(diff[i]) / (abs(diff[i]) + abs(diff[i + 1]))
            eer = float((1 - w) * fpr[i] + w * fpr[i + 1])
            # Threshold closer to the smaller |diff|.
            thr_e = float((1 - w) * thr[i] + w * thr[i + 1])
            return eer, thr_e
    idx = int(np.argmin(np.abs(diff)))
    return float(0.5 * (fpr[idx] + fnr[idx])), float(thr[idx])


def min_tdcf(
    labels: Sequence[int] | NDArray[np.integer],
    scores: Sequence[float] | NDArray[np.floating],
    *,
    c_miss: float = ASVSPOOF2019_C_MISS,
    c_fa: float = ASVSPOOF2019_C_FA,
    p_spoof: float = ASVSPOOF2019_P_SPOOF,
) -> dict[str, float]:
    """Minimum normalised t-DCF (ASVspoof 2019 CM formulation).

    t-DCF(θ) = C_miss · π_spoof · P_miss(θ) + C_fa · (1−π_spoof) · P_fa(θ)
    normalised by min(C_miss·π_spoof, C_fa·(1−π_spoof)).
    """
    y, s = _as_arrays(labels, scores)
    if y.size == 0 or np.unique(y).size < 2:
        return {"min_tdcf": 1.0, "threshold": 0.0, "p_miss": 1.0, "p_fa": 1.0}
    p_bona = 1.0 - p_spoof
    n_pos = float(np.sum(y == 1))
    n_neg = float(np.sum(y == 0))
    c_default = min(c_miss * p_spoof, c_fa * p_bona)
    best = {
        "min_tdcf": 1.0,
        "threshold": float(np.median(s)),
        "p_miss": 1.0,
        "p_fa": 1.0,
    }
    for thr in np.unique(s):
        pred = s >= thr
        p_miss = float(np.sum((y == 1) & ~pred)) / max(n_pos, 1.0)
        p_fa = float(np.sum((y == 0) & pred)) / max(n_neg, 1.0)
        tdcf = c_miss * p_spoof * p_miss + c_fa * p_bona * p_fa
        norm = tdcf / max(c_default, 1e-12)
        if norm < best["min_tdcf"]:
            best = {
                "min_tdcf": float(norm),
                "threshold": float(thr),
                "p_miss": float(p_miss),
                "p_fa": float(p_fa),
            }
    return best


def fpr_at_tpr(
    labels: Sequence[int] | NDArray[np.integer],
    scores: Sequence[float] | NDArray[np.floating],
    target_tpr: float,
) -> dict[str, float]:
    """Smallest FPR achieving TPR ≥ target (bank-operational metric)."""
    fpr, tpr, thr = roc_curve(labels, scores)
    mask = tpr >= target_tpr
    if not np.any(mask):
        return {"target_tpr": float(target_tpr), "fpr": 1.0, "threshold": float(thr[-1]), "achieved_tpr": float(tpr[-1])}
    idx = int(np.argmax(mask))  # first True along increasing TPR after ROC build
    # Prefer the point with minimal FPR among those meeting TPR.
    candidates = np.where(mask)[0]
    idx = int(candidates[np.argmin(fpr[candidates])])
    return {
        "target_tpr": float(target_tpr),
        "fpr": float(fpr[idx]),
        "threshold": float(thr[idx]),
        "achieved_tpr": float(tpr[idx]),
    }


def expected_calibration_error(
    probs: Sequence[float] | NDArray[np.floating],
    labels: Sequence[int] | NDArray[np.integer],
    n_bins: int = 10,
) -> dict[str, Any]:
    """ECE over equal-width probability bins (Naeini et al. / Guo et al.)."""
    p = np.asarray(probs, dtype=np.float64)
    y = np.asarray(labels, dtype=np.float64)
    if p.size == 0:
        return {"ece": 0.0, "bins": []}
    bins = np.linspace(0.0, 1.0, n_bins + 1)
    ece = 0.0
    detail: list[dict[str, float]] = []
    for i in range(n_bins):
        lo, hi = bins[i], bins[i + 1]
        mask = (p >= lo) & (p < hi if i < n_bins - 1 else p <= hi)
        if not np.any(mask):
            detail.append({"lo": float(lo), "hi": float(hi), "count": 0, "confidence": 0.0, "accuracy": 0.0})
            continue
        conf = float(np.mean(p[mask]))
        acc = float(np.mean(y[mask]))
        weight = float(np.sum(mask)) / p.size
        ece += weight * abs(acc - conf)
        detail.append(
            {
                "lo": float(lo),
                "hi": float(hi),
                "count": float(np.sum(mask)),
                "confidence": conf,
                "accuracy": acc,
            }
        )
    return {"ece": float(ece), "bins": detail}


def reliability_diagram(
    probs: Sequence[float] | NDArray[np.floating],
    labels: Sequence[int] | NDArray[np.integer],
    out_path: Path,
    *,
    title: str = "Reliability diagram",
    n_bins: int = 10,
) -> Path:
    """Write a reliability-diagram PNG (confidence vs empirical accuracy)."""
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    cal = expected_calibration_error(probs, labels, n_bins=n_bins)
    centers = []
    accs = []
    confs = []
    counts = []
    for b in cal["bins"]:
        centers.append(0.5 * (b["lo"] + b["hi"]))
        accs.append(b["accuracy"])
        confs.append(b["confidence"])
        counts.append(b["count"])

    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig, ax = plt.subplots(figsize=(5.5, 5.0))
    ax.plot([0, 1], [0, 1], "k--", lw=1, label="perfect")
    width = 1.0 / n_bins * 0.85
    ax.bar(centers, accs, width=width, color="#1A567A", alpha=0.75, label="empirical accuracy")
    ax.plot(centers, confs, "o-", color="#B03A2E", label="mean confidence")
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    ax.set_xlabel("Predicted spoof probability")
    ax.set_ylabel("Fraction of spoofs / mean confidence")
    ax.set_title(f"{title}\nECE={cal['ece']:.4f}")
    ax.legend(loc="upper left", fontsize=8)
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    fig.savefig(out_path, dpi=140)
    plt.close(fig)
    return out_path


def summarise_detection(
    labels: Sequence[int] | NDArray[np.integer],
    scores: Sequence[float] | NDArray[np.floating],
    probs: Optional[Sequence[float] | NDArray[np.floating]] = None,
) -> dict[str, Any]:
    """Full detection metric block for one (dataset × condition × model) cell."""
    y, s = _as_arrays(labels, scores)
    if probs is None:
        # Map logits → [0,1] via sigmoid for calibration / reliability plots.
        probs_arr = 1.0 / (1.0 + np.exp(-np.clip(s, -40, 40)))
    else:
        probs_arr = np.asarray(probs, dtype=np.float64)

    eer, eer_thr = eer_and_threshold(y, s)
    tdcf = min_tdcf(y, s)
    out: dict[str, Any] = {
        "n": int(y.size),
        "n_bonafide": int(np.sum(y == 0)),
        "n_spoof": int(np.sum(y == 1)),
        "eer": round(eer, 6),
        "eer_threshold": round(eer_thr, 6),
        "min_tdcf": round(tdcf["min_tdcf"], 6),
        "min_tdcf_threshold": round(tdcf["threshold"], 6),
        "auc": round(auc_score(y, s), 6),
        "fpr_at_tpr": {
            "0.80": fpr_at_tpr(y, s, 0.80),
            "0.90": fpr_at_tpr(y, s, 0.90),
            "0.95": fpr_at_tpr(y, s, 0.95),
        },
        "ece": round(expected_calibration_error(probs_arr, y)["ece"], 6),
        "tdcf_citation": (
            "ASVspoof 2019 CM t-DCF (Todisco et al., Interspeech 2019); "
            f"C_miss={ASVSPOOF2019_C_MISS}, C_fa={ASVSPOOF2019_C_FA}, "
            f"pi_spoof={ASVSPOOF2019_P_SPOOF}"
        ),
    }
    return out


def percentile(values: Sequence[float], q: float) -> float:
    if not values:
        return 0.0
    return float(np.percentile(np.asarray(values, dtype=np.float64), q))


def latency_summary(samples_ms: Sequence[float]) -> dict[str, float]:
    return {
        "n": float(len(samples_ms)),
        "p50_ms": round(percentile(samples_ms, 50), 3),
        "p95_ms": round(percentile(samples_ms, 95), 3),
        "p99_ms": round(percentile(samples_ms, 99), 3),
        "mean_ms": round(float(np.mean(samples_ms)) if samples_ms else 0.0, 3),
    }
