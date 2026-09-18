"""Channel forensics (Context §10.4 / §3.2).

Blind T60, double-compression score, noise-floor stationarity, and codec/bandwidth
fingerprinting. These features answer the dual-profile question and catch media injection.

Caveats the fusion engine MUST respect:
  - T60 alone is never decisive: close-talk headsets and codec NS can flatten tails.
  - Noise-floor stationarity alone is never decisive: codec comfort-noise is also stationary.
  - Double-compression is STRONGER on narrowband telephony codecs than on clean wideband.
"""

from __future__ import annotations

from typing import Any, Optional

import numpy as np
from numpy.typing import NDArray
from scipy.signal import medfilt

from app.types import ChannelProfile

_HOP = 256
_FRAME = 512
_T60_PLAUSIBLE_LO_MS = 100.0
_T60_PLAUSIBLE_HI_MS = 800.0
_T60_INJECTED_MS = 30.0

# Session-level bandwidth history for mid-call change detection (re-INVITE).
_bw_history: dict[str, list[float]] = {}


def reset_bandwidth_history(session_id: Optional[str] = None) -> None:
    """Clear mid-call bandwidth trackers (tests / session close)."""
    global _bw_history
    if session_id is None:
        _bw_history = {}
    else:
        _bw_history.pop(session_id, None)


def _rms_frames(audio: NDArray[np.floating], frame: int = _FRAME, hop: int = _HOP) -> NDArray[np.floating]:
    if audio.size < frame:
        return np.asarray([np.sqrt(np.mean(audio**2) + 1e-20)], dtype=np.float64)
    n = 1 + (audio.size - frame) // hop
    out = np.empty(n, dtype=np.float64)
    for i in range(n):
        seg = audio[i * hop : i * hop + frame]
        out[i] = np.sqrt(np.mean(seg**2) + 1e-20)
    return out


def _speech_mask(rms: NDArray[np.floating]) -> NDArray[np.bool_]:
    thr = 0.3 * float(np.percentile(rms, 90) + 1e-12)
    return rms >= thr


def estimate_t60(
    audio: NDArray[np.floating],
    sr: int,
) -> dict[str, Any]:
    """Blind T60 from energy decay after speech offsets (Schroeder-style).

    Plausibility: live mics in real rooms typically yield ~100–800 ms.
    Near-zero T60 (<30 ms) suggests digitally injected audio with no acoustic path.

    Caveat: a close-talking headset in a treated room can be genuinely low, and phone
    noise-suppression can flatten the tail. Always return ``t60_confidence`` and never
    assert authenticity on T60 alone.
    """
    samples = np.asarray(audio, dtype=np.float64).reshape(-1)
    if samples.size < sr // 4:
        return {
            "t60_ms": None,
            "t60_confidence": 0.0,
            "rir_plausible": False,
            "reason": "audio_too_short",
        }

    hop = max(64, sr // 200)
    frame = hop * 2
    rms = _rms_frames(samples, frame=frame, hop=hop)
    speech = _speech_mask(rms)
    hop_s = hop / float(sr)

    # Noise floor for Lundeby-style truncation of the Schroeder integral.
    noise_rms = float(np.percentile(rms, 15))
    noise_pow = noise_rms**2

    # Find offsets: speech -> non-speech transitions with enough following silence.
    estimates: list[float] = []
    confidences: list[float] = []
    min_tail = int(0.08 / hop_s)
    max_tail = int(0.8 / hop_s)
    for i in range(1, speech.size - min_tail):
        if not (speech[i - 1] and not speech[i]):
            continue
        # Peak speech level just before offset — free decay starts once we are
        # well below it (avoids the speech-offset transition bias).
        pre = rms[max(0, i - int(0.1 / hop_s)) : i]
        if pre.size == 0:
            continue
        peak = float(np.max(pre))
        if peak <= noise_rms * 3:
            continue

        raw_tail = rms[i : i + max_tail]
        if raw_tail.size < min_tail:
            continue

        # Skip into free decay: first frame ≤ −8 dB re peak.
        free_start = 0
        thr = peak * (10.0 ** (-8.0 / 20.0))
        for j, v in enumerate(raw_tail):
            if v <= thr:
                free_start = j
                break
        else:
            continue
        tail = raw_tail[free_start:]
        if tail.size < min_tail:
            continue

        # Schroeder backward integration with noise-floor subtraction.
        power = np.maximum(tail**2 - noise_pow, 0.0)
        if float(np.max(power)) <= 0:
            continue
        sch = np.cumsum(power[::-1])[::-1]
        # Truncate where Schroeder falls into the noise plateau.
        sch = sch / (sch[0] + 1e-20)
        db = 10.0 * np.log10(sch + 1e-20)
        # Fit decay between -5 and -25 dB (T20 region), extrapolate to T60.
        mask = (db <= -5.0) & (db >= -25.0)
        if np.sum(mask) < 4:
            mask = (db <= -5.0) & (db >= -35.0)
        if np.sum(mask) < 4:
            continue
        t = np.arange(db.size)[mask] * hop_s
        y = db[mask]
        # Linear regression y = a + b t ; T60 = -60 / b
        b, a = np.polyfit(t, y, 1)
        if b >= -1e-6:
            continue
        t60 = -60.0 / b
        if not np.isfinite(t60) or t60 <= 0 or t60 > 5.0:
            continue
        # Confidence from fit R² and dynamic range covered.
        y_hat = a + b * t
        ss_res = float(np.sum((y - y_hat) ** 2))
        ss_tot = float(np.sum((y - np.mean(y)) ** 2)) + 1e-12
        r2 = max(0.0, 1.0 - ss_res / ss_tot)
        span = float(np.max(y) - np.min(y))
        conf = float(np.clip(0.5 * r2 + 0.5 * min(span / 20.0, 1.0), 0.0, 1.0))
        estimates.append(t60)
        confidences.append(conf)

    if not estimates:
        # No clean offset: fall back to global envelope decay rate (low confidence).
        env = medfilt(rms, kernel_size=5)
        peak_i = int(np.argmax(env))
        tail = env[peak_i:]
        if tail.size > 8:
            db = 20.0 * np.log10(tail / (tail[0] + 1e-20) + 1e-20)
            mask = (db <= -5.0) & (db >= -35.0)
            if np.sum(mask) >= 4:
                t = np.arange(db.size)[mask] * hop_s
                y = db[mask]
                b, _ = np.polyfit(t, y, 1)
                if b < -1e-6:
                    t60 = -60.0 / b
                    if 0 < t60 < 5.0:
                        estimates = [t60]
                        confidences = [0.25]

    if not estimates:
        return {
            "t60_ms": 0.0,
            "t60_confidence": 0.1,
            "rir_plausible": False,
            "reason": "no_decay_region",
        }

    # Confidence-weighted median (prefer well-fit free-decay segments).
    order = np.argsort(estimates)
    est_arr = np.asarray(estimates, dtype=np.float64)[order]
    conf_arr = np.asarray(confidences, dtype=np.float64)[order]
    # Drop low-confidence outliers when we have enough samples.
    if est_arr.size >= 3:
        keep = conf_arr >= max(0.35, float(np.median(conf_arr)) * 0.6)
        if np.sum(keep) >= 2:
            est_arr = est_arr[keep]
            conf_arr = conf_arr[keep]
    t60_s = float(np.median(est_arr))
    t60_ms = t60_s * 1000.0
    conf = float(np.median(conf_arr))
    plausible = _T60_PLAUSIBLE_LO_MS <= t60_ms <= _T60_PLAUSIBLE_HI_MS
    return {
        "t60_ms": t60_ms,
        "t60_confidence": conf,
        "rir_plausible": bool(plausible),
        "t60_suspicious_injection": bool(t60_ms < _T60_INJECTED_MS),
    }


def _mdct_like_coeffs(audio: NDArray[np.floating], n: int = 512) -> NDArray[np.floating]:
    """Overlapped DCT-II frames as a lightweight MDCT proxy for quantisation forensics."""
    from scipy.fftpack import dct

    hop = n // 2
    samples = np.ascontiguousarray(audio, dtype=np.float64)
    if samples.size < n:
        pad = np.zeros(n, dtype=np.float64)
        pad[: samples.size] = samples
        frames = pad[None, :]
    else:
        n_frames = 1 + (samples.size - n) // hop
        frames = np.stack([samples[i * hop : i * hop + n] for i in range(n_frames)])
    window = np.hanning(n)
    windowed = frames * window
    return dct(windowed, type=2, norm="ortho", axis=1)


def double_compression_score(audio: NDArray[np.floating], sr: int) -> dict[str, Any]:
    """Score likelihood of two quantisation stages (render codec + telephony codec).

    Approach: histogram of quantised MDCT-like coefficients + Benford first-digit
    deviation and periodicity (bin-ripple) energy, plus sample-level µ-law / PCM
    staircase sparsity on an 8 kHz-decimated view (survives 8→16 kHz upsampling).

    This feature is STRONGER on narrowband (G.711 / AMR-NB): telephony re-encoding on top
    of a prior lossy render leaves clearer dual-quantisation structure than a single
    clean wideband encode.
    """
    samples = np.asarray(audio, dtype=np.float64).reshape(-1)
    if samples.size < 1024:
        return {
            "double_compression_score": 0.0,
            "codec_family": "unknown",
            "benford_deviation": 0.0,
        }

    coeffs = _mdct_like_coeffs(samples)
    flat = np.abs(coeffs).reshape(-1)
    flat = flat[flat > 1e-8]
    if flat.size < 100:
        return {
            "double_compression_score": 0.0,
            "codec_family": "unknown",
            "benford_deviation": 0.0,
        }

    # Quantise to emulate codec coefficient bins.
    scale = np.percentile(flat, 95) + 1e-12
    q = np.round(flat / scale * 64.0)
    q = q[q > 0]

    # Benford first-digit law on positive magnitudes.
    mantissa = flat / (10.0 ** np.floor(np.log10(flat)))
    first = np.clip(mantissa.astype(int), 1, 9)
    hist = np.bincount(first, minlength=10)[1:10].astype(np.float64)
    hist = hist / (hist.sum() + 1e-12)
    benford = np.log10(1.0 + 1.0 / np.arange(1, 10))
    benford = benford / benford.sum()
    benford_dev = float(np.sum(np.abs(hist - benford)))

    # Periodicity / ripple in fine histogram of quantised coeffs.
    q_hist, _ = np.histogram(q, bins=64, range=(0, 64), density=True)
    spec = np.abs(np.fft.rfft(q_hist - np.mean(q_hist)))
    ripple = float(np.sum(spec[3:]) / (np.sum(spec) + 1e-12))

    # G.711 lattice sparsity on decimated samples (upsamplers re-densify 16 kHz PCM).
    dec_hop = max(1, int(round(sr / 8000.0)))
    dec = samples[::dec_hop]
    peak = float(np.max(np.abs(dec))) + 1e-12
    pcm8 = np.round(dec / peak * 32767.0)
    n_unique = float(np.unique(pcm8).size)
    # Clean float / PCM16 ≈ thousands of levels; µ-law ≈ ≤256.
    sparsity = float(np.clip((512.0 - n_unique) / 400.0, 0.0, 1.0))

    # High-band quantisation noise after a brick wall (lossy + telephony).
    if samples.size >= 2048:
        win = samples * np.hanning(samples.size)
        psd = np.abs(np.fft.rfft(win)) ** 2
        freqs = np.fft.rfftfreq(samples.size, d=1.0 / sr)
        mid = float(np.mean(psd[(freqs >= 1000) & (freqs < 3000)]) + 1e-20)
        high = float(np.mean(psd[(freqs >= 3500) & (freqs < min(7900, sr / 2 - 50))]) + 1e-20)
        # Clean wideband: high≈mid; NB/mulaw: high << mid; MP3+mulaw: mid also notched.
        brick = float(np.clip(1.0 - high / mid, 0.0, 1.0))
    else:
        brick = 0.0

    # Extra MDCT "dead zone" density: dual quantisation piles mass near small bins.
    small = float(np.mean(q <= 2))
    deadzone = float(np.clip((small - 0.15) / 0.35, 0.0, 1.0))

    score = float(
        np.clip(
            0.15 * min(benford_dev / 0.40, 1.0)
            + 0.15 * min(ripple / 0.55, 1.0)
            + 0.40 * sparsity
            + 0.20 * brick
            + 0.10 * deadzone,
            0.0,
            1.0,
        )
    )

    bw = effective_bandwidth_hz(samples, sr)
    if bw < 3800:
        family = "narrowband_telephony"
    elif bw < 7500:
        family = "wideband_voip"
    else:
        family = "fullband_or_pcm"

    return {
        "double_compression_score": score,
        "codec_family": family,
        "benford_deviation": benford_dev,
        "histogram_ripple": ripple,
        "amplitude_sparsity": sparsity,
        "bandwidth_brick_score": brick,
    }


def noise_floor_analysis(audio: NDArray[np.floating], sr: int) -> dict[str, Any]:
    """Noise floor in non-speech segments + stationarity.

    Real rooms are non-stationary; synthetic silence / comfort noise is suspiciously
    constant. CONFOUND: codec comfort-noise generation ALSO produces stationary silence,
    so ``noise_floor_stationarity`` must not fire alone in the fusion engine.
    """
    samples = np.asarray(audio, dtype=np.float64).reshape(-1)
    rms = _rms_frames(samples)
    speech = _speech_mask(rms)
    noise = rms[~speech]
    if noise.size < 3:
        noise = rms[rms <= np.percentile(rms, 30)]
    if noise.size == 0:
        return {
            "noise_floor_db": -80.0,
            "noise_floor_stationarity": 0.0,
        }
    floor = float(np.median(noise))
    floor_db = float(20.0 * np.log10(floor + 1e-12))
    # Stationarity: 1 = perfectly constant across the window.
    rel = float(np.std(noise) / (np.mean(noise) + 1e-12))
    stationarity = float(np.clip(1.0 - rel, 0.0, 1.0))
    return {
        "noise_floor_db": floor_db,
        "noise_floor_stationarity": stationarity,
    }


def effective_bandwidth_hz(audio: NDArray[np.floating], sr: int, power_frac: float = 0.99) -> float:
    """Highest frequency containing meaningful cumulative energy."""
    samples = np.asarray(audio, dtype=np.float64).reshape(-1)
    if samples.size < 64:
        return 0.0
    win = samples * np.hanning(samples.size)
    spec = np.abs(np.fft.rfft(win)) ** 2
    freqs = np.fft.rfftfreq(samples.size, d=1.0 / sr)
    csum = np.cumsum(spec)
    total = float(csum[-1]) + 1e-20
    idx = int(np.searchsorted(csum, power_frac * total))
    idx = min(idx, freqs.size - 1)
    return float(freqs[idx])


def codec_bandwidth_fingerprint(
    audio: NDArray[np.floating],
    sr: int,
    *,
    session_id: Optional[str] = None,
    previous_bandwidth_hz: Optional[float] = None,
) -> dict[str, Any]:
    """Detect NB (~3.4 kHz) / WB (~7 kHz) brick walls and mid-call bandwidth changes."""
    bw = effective_bandwidth_hz(audio, sr)
    if bw < 4000:
        profile_guess = "PSTN_NARROWBAND"
        brick = "narrowband_3k4"
    elif bw < 7500:
        profile_guess = "VOIP_WIDEBAND"
        brick = "wideband_7k"
    else:
        profile_guess = "WEBRTC_WIDEBAND"
        brick = "fullband"

    mid_call_change = False
    prev = previous_bandwidth_hz
    if session_id is not None:
        hist = _bw_history.setdefault(session_id, [])
        if hist:
            prev = hist[-1]
        hist.append(bw)
        # Keep short history.
        if len(hist) > 64:
            del hist[:-32]
    if prev is not None and prev > 0:
        # Abrupt jump across the NB/WB boundary → possible re-INVITE / injection.
        crossed = (prev < 4500 <= bw) or (bw < 4500 <= prev) or (prev < 7500 <= bw) or (bw < 7500 <= prev)
        rel = abs(bw - prev) / max(prev, 1.0)
        mid_call_change = bool(crossed or rel > 0.35)

    return {
        "effective_bandwidth_hz": bw,
        "bandwidth_brick": brick,
        "profile_guess": profile_guess,
        "mid_call_bandwidth_change": mid_call_change,
        "previous_bandwidth_hz": prev,
    }


def dc_and_clipping(audio: NDArray[np.floating]) -> dict[str, float]:
    samples = np.asarray(audio, dtype=np.float64).reshape(-1)
    if samples.size == 0:
        return {"dc_offset": 0.0, "clipping_ratio": 0.0}
    dc = float(np.mean(samples))
    clip = float(np.mean(np.abs(samples) >= 0.99))
    return {"dc_offset": dc, "clipping_ratio": clip}


def extract(
    audio: np.ndarray,
    sr: int,
    profile: ChannelProfile | str | None = None,
    *,
    session_id: Optional[str] = None,
    previous_bandwidth_hz: Optional[float] = None,
) -> dict[str, Any]:
    """Full channel-forensics feature dict for one window."""
    samples = np.asarray(audio, dtype=np.float64).reshape(-1)
    if samples.size == 0 or sr <= 0:
        return {"available": False, "reason": "empty_audio"}

    peak = float(np.max(np.abs(samples)))
    if peak > 1e-8:
        samples = samples / peak

    t60 = estimate_t60(samples, sr)
    dcomp = double_compression_score(samples, sr)
    noise = noise_floor_analysis(samples, sr)
    bw = codec_bandwidth_fingerprint(
        samples, sr, session_id=session_id, previous_bandwidth_hz=previous_bandwidth_hz
    )
    dc = dc_and_clipping(np.asarray(audio, dtype=np.float64).reshape(-1))

    # Narrowband gets a mild boost note for fusion (score itself unchanged; flag for reweight).
    narrow = False
    if profile is not None:
        value = profile.value if isinstance(profile, ChannelProfile) else str(profile)
        narrow = value == ChannelProfile.PSTN_NARROWBAND.value

    return {
        "available": True,
        "t60_ms": t60.get("t60_ms"),
        "t60_confidence": t60.get("t60_confidence", 0.0),
        "rir_plausible": t60.get("rir_plausible", False),
        "t60_suspicious_injection": t60.get("t60_suspicious_injection", False),
        "double_compression_score": dcomp["double_compression_score"],
        "codec_family": dcomp["codec_family"],
        "double_compression_stronger_on_narrowband": True,
        "narrowband_profile": narrow,
        "noise_floor_db": noise["noise_floor_db"],
        "noise_floor_stationarity": noise["noise_floor_stationarity"],
        **bw,
        **dc,
    }
