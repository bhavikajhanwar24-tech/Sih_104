"""Shared LFCC-Δ-ΔΔ features and Tier-1 LCNN for anti-spoofing."""

from __future__ import annotations

from typing import Optional

import numpy as np
import torch
import torch.nn as nn
from numpy.typing import NDArray
from scipy.fftpack import dct

N_LFCC = 20
N_FILTERS = 26
N_FFT = 512
HOP = 160  # 10 ms @ 16 kHz
SR = 16000
STACK_DIM = N_LFCC * 3  # static + delta + delta-delta


def _linear_filterbank(sr: int, n_fft: int, n_filters: int, fmax: float) -> NDArray[np.floating]:
    n_bins = n_fft // 2 + 1
    points = np.linspace(0.0, fmax, n_filters + 2)
    bin_freqs = np.linspace(0.0, float(sr) / 2.0, n_bins)
    fb = np.zeros((n_filters, n_bins), dtype=np.float64)
    for i in range(n_filters):
        left, center, right = points[i], points[i + 1], points[i + 2]
        rising = (bin_freqs >= left) & (bin_freqs <= center)
        falling = (bin_freqs > center) & (bin_freqs <= right)
        if center > left:
            fb[i, rising] = (bin_freqs[rising] - left) / (center - left)
        if right > center:
            fb[i, falling] = (right - bin_freqs[falling]) / (right - center)
    return fb


def _delta(coeffs: NDArray[np.floating], width: int = 2) -> NDArray[np.floating]:
    if coeffs.shape[1] < 2:
        return np.zeros_like(coeffs)
    padded = np.pad(coeffs, ((0, 0), (width, width)), mode="edge")
    denom = 2.0 * sum(i * i for i in range(1, width + 1))
    out = np.zeros_like(coeffs)
    for i in range(1, width + 1):
        out += i * (
            padded[:, width + i : width + i + coeffs.shape[1]]
            - padded[:, width - i : width - i + coeffs.shape[1]]
        )
    return out / denom


def lfcc_stack(
    audio: NDArray[np.floating],
    sr: int = SR,
    *,
    max_frames: Optional[int] = 200,
) -> NDArray[np.floating]:
    """Return (STACK_DIM, T) LFCC + delta + delta-delta."""
    samples = np.asarray(audio, dtype=np.float64).reshape(-1)
    if samples.size < N_FFT:
        samples = np.pad(samples, (0, N_FFT - samples.size))
    window = np.hanning(N_FFT)
    n_frames = 1 + (samples.size - N_FFT) // HOP
    frames = np.stack([samples[i * HOP : i * HOP + N_FFT] * window for i in range(n_frames)])
    mag = np.abs(np.fft.rfft(frames, axis=1)).T  # (freq, time)
    fmax = min(8000.0, float(sr) / 2.0)
    fb = _linear_filterbank(sr, N_FFT, N_FILTERS, fmax)
    n = min(fb.shape[1], mag.shape[0])
    filtered = fb[:, :n] @ (mag[:n, :] ** 2)
    log_spec = np.log(np.maximum(filtered, 1e-10))
    lfcc = dct(log_spec, type=2, axis=0, norm="ortho")[:N_LFCC, :]
    d1 = _delta(lfcc)
    d2 = _delta(d1)
    stack = np.vstack([lfcc, d1, d2]).astype(np.float32)
    if max_frames is not None and stack.shape[1] > max_frames:
        stack = stack[:, :max_frames]
    # CMVN per utterance
    stack = (stack - stack.mean(axis=1, keepdims=True)) / (stack.std(axis=1, keepdims=True) + 1e-6)
    return stack


class TinyLCNN(nn.Module):
    """Minimal LCNN-style stack on LFCC-Δ-ΔΔ (Tier 1). CPU-friendly."""

    def __init__(self, n_feat: int = STACK_DIM) -> None:
        super().__init__()
        self.features = nn.Sequential(
            nn.Conv2d(1, 16, kernel_size=3, padding=1),
            nn.BatchNorm2d(16),
            nn.ReLU(inplace=True),
            nn.MaxPool2d((2, 2)),
            nn.Conv2d(16, 32, kernel_size=3, padding=1),
            nn.BatchNorm2d(32),
            nn.ReLU(inplace=True),
            nn.MaxPool2d((2, 2)),
            nn.Conv2d(32, 64, kernel_size=3, padding=1),
            nn.BatchNorm2d(64),
            nn.ReLU(inplace=True),
            nn.AdaptiveAvgPool2d((1, 1)),
        )
        self.fc = nn.Linear(64, 1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: (B, 1, F, T)
        h = self.features(x)
        h = h.view(h.size(0), -1)
        return self.fc(h).squeeze(-1)  # logits (spoof-positive)


def pad_stack(stack: NDArray[np.floating], max_frames: int = 200) -> NDArray[np.floating]:
    f, t = stack.shape
    if t >= max_frames:
        return stack[:, :max_frames]
    out = np.zeros((f, max_frames), dtype=np.float32)
    out[:, :t] = stack
    return out
