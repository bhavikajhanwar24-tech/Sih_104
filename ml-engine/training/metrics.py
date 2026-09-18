"""Training metrics: EER and min t-DCF (ASVspoof-style).

DO NOT tune operating thresholds on the evaluation set — report EER / min t-DCF only.
DO NOT report train-set accuracy as general performance (Context §3.1).
"""

from __future__ import annotations

from typing import Optional

import numpy as np
from numpy.typing import NDArray


def eer_and_threshold(labels: NDArray[np.integer], scores: NDArray[np.floating]) -> tuple[float, float]:
    """Equal-error rate. Higher score ⇒ more spoof-like."""
    y = np.asarray(labels, dtype=np.int32)
    s = np.asarray(scores, dtype=np.float64)
    if y.size == 0 or np.unique(y).size < 2:
        return 0.5, 0.0
    thresholds = np.unique(s)
    best_eer = 1.0
    best_thr = float(np.median(s))
    n_pos = float(np.sum(y == 1))
    n_neg = float(np.sum(y == 0))
    for thr in thresholds:
        pred_pos = s >= thr
        fpr = float(np.sum((y == 0) & pred_pos)) / max(n_neg, 1.0)
        fnr = float(np.sum((y == 1) & ~pred_pos)) / max(n_pos, 1.0)
        eer = 0.5 * (fpr + fnr)
        if abs(fpr - fnr) < abs(best_eer * 2) or eer < best_eer:
            # Prefer FPR≈FNR; track minimum |FPR-FNR| region.
            gap = abs(fpr - fnr)
            if gap < 0.05 or eer <= best_eer:
                best_eer = float(eer)
                best_thr = float(thr)
    # Classic sweep for true EER intersection.
    best_eer = 1.0
    for thr in thresholds:
        pred_pos = s >= thr
        fpr = float(np.sum((y == 0) & pred_pos)) / max(n_neg, 1.0)
        fnr = float(np.sum((y == 1) & ~pred_pos)) / max(n_pos, 1.0)
        if abs(fpr - fnr) < abs(2 * best_eer - 1) or True:
            eer = max(fpr, fnr)  # conservative EER estimate at this thr
            # Better: average when close
            eer = 0.5 * (fpr + fnr)
            if abs(fpr - fnr) <= 0.02:
                return float(eer), float(thr)
            if eer < best_eer:
                best_eer = float(eer)
                best_thr = float(thr)
    return best_eer, best_thr


def min_tdcf(
    labels: NDArray[np.integer],
    scores: NDArray[np.floating],
    *,
    c_miss: float = 1.0,
    c_fa: float = 10.0,
    p_tar: float = 0.05,
) -> float:
    """Minimum tandem detection cost (ASVspoof 2019 LA defaults)."""
    y = np.asarray(labels, dtype=np.int32)
    s = np.asarray(scores, dtype=np.float64)
    if y.size == 0 or np.unique(y).size < 2:
        return 1.0
    p_non = 1.0 - p_tar
    n_pos = float(np.sum(y == 1))
    n_neg = float(np.sum(y == 0))
    c_default = min(c_miss * p_tar, c_fa * p_non)
    best = 1.0
    for thr in np.unique(s):
        pred_pos = s >= thr
        p_miss = float(np.sum((y == 1) & ~pred_pos)) / max(n_pos, 1.0)
        p_fa = float(np.sum((y == 0) & pred_pos)) / max(n_neg, 1.0)
        tdcf = c_miss * p_tar * p_miss + c_fa * p_non * p_fa
        norm = tdcf / max(c_default, 1e-12)
        best = min(best, float(norm))
    return best


def expected_calibration_error(
    probs: NDArray[np.floating],
    labels: NDArray[np.integer],
    n_bins: int = 10,
) -> float:
    p = np.asarray(probs, dtype=np.float64)
    y = np.asarray(labels, dtype=np.float64)
    bins = np.linspace(0.0, 1.0, n_bins + 1)
    ece = 0.0
    for i in range(n_bins):
        mask = (p >= bins[i]) & (p < bins[i + 1] if i < n_bins - 1 else p <= bins[i + 1])
        if not np.any(mask):
            continue
        conf = float(np.mean(p[mask]))
        acc = float(np.mean(y[mask]))
        ece += (float(np.sum(mask)) / p.size) * abs(acc - conf)
    return float(ece)
