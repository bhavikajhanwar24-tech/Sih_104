from __future__ import annotations

from math import gcd
from typing import Optional

import numpy as np
from scipy.signal import resample_poly

from app.types import ChannelProfile

_OUT_RATE = 16000
_SUPPORTED = frozenset({"pcm_s16le", "pcm_f32le", "mulaw", "alaw", "slin16"})


def _ulaw_decode_table() -> np.ndarray:
    table = np.empty(256, dtype=np.int16)
    for i in range(256):
        inv = (~i) & 0xFF
        sign = inv & 0x80
        exponent = (inv >> 4) & 0x07
        mantissa = inv & 0x0F
        mag = (((mantissa << 3) + 0x84) << exponent) - 0x84
        table[i] = np.int16(-mag if sign else mag)
    return table


def _alaw_decode_table() -> np.ndarray:
    table = np.empty(256, dtype=np.int16)
    for i in range(256):
        a = i ^ 0x55
        sign = a & 0x80
        exponent = (a >> 4) & 0x07
        mantissa = a & 0x0F
        if exponent == 0:
            mag = (mantissa << 4) + 8
        else:
            mag = ((mantissa << 4) + 0x108) << (exponent - 1)
        table[i] = np.int16(-mag if sign else mag)
    return table


_ULAW_TABLE = _ulaw_decode_table()
_ALAW_TABLE = _alaw_decode_table()


def _decode_pcm(raw: bytes, encoding: str) -> np.ndarray:
    enc = encoding.lower()
    if enc in ("pcm_s16le", "slin16"):
        if len(raw) % 2:
            raw = raw[:-1]
        samples = np.frombuffer(raw, dtype="<i2")
        return samples.astype(np.float32) / 32768.0
    if enc == "pcm_f32le":
        leftover = len(raw) % 4
        if leftover:
            raw = raw[: len(raw) - leftover]
        return np.frombuffer(raw, dtype="<f4").astype(np.float32, copy=True)
    if enc == "mulaw":
        idx = np.frombuffer(raw, dtype=np.uint8)
        return _ULAW_TABLE[idx].astype(np.float32) / 32768.0
    if enc == "alaw":
        idx = np.frombuffer(raw, dtype=np.uint8)
        return _ALAW_TABLE[idx].astype(np.float32) / 32768.0
    raise ValueError(f"unsupported encoding: {encoding}")


def _downmix(pcm: np.ndarray, channels: int) -> np.ndarray:
    if channels <= 1:
        return np.asarray(pcm, dtype=np.float32).reshape(-1)
    if pcm.size % channels != 0:
        pcm = pcm[: pcm.size - (pcm.size % channels)]
    framed = pcm.reshape(-1, channels)
    return framed.mean(axis=1, dtype=np.float32)


def _resample(pcm: np.ndarray, in_rate: int) -> np.ndarray:
    if in_rate == _OUT_RATE:
        return pcm
    if in_rate <= 0:
        raise ValueError("in_rate must be positive")
    g = gcd(_OUT_RATE, in_rate)
    up = _OUT_RATE // g
    down = in_rate // g
    return resample_poly(pcm, up, down).astype(np.float32)


def infer_channel_profile(
    encoding: str,
    in_rate: int,
    source: Optional[str] = None,
    profile: Optional[ChannelProfile | str] = None,
) -> ChannelProfile:
    if profile is not None:
        return profile if isinstance(profile, ChannelProfile) else ChannelProfile(profile)
    enc = encoding.lower()
    if enc in ("mulaw", "alaw") or in_rate <= 8000:
        return ChannelProfile.PSTN_NARROWBAND
    src = (source or "").lower()
    if in_rate >= 16000 and src in {"browser", "webrtc", "webrtc_wideband"}:
        return ChannelProfile.WEBRTC_WIDEBAND
    return ChannelProfile.VOIP_WIDEBAND


def normalise(
    raw: bytes,
    encoding: str,
    in_rate: int,
    channels: int,
    profile: Optional[ChannelProfile | str] = None,
    source: Optional[str] = None,
) -> tuple[np.ndarray, ChannelProfile]:
    """Decode, downmix, resample to 16 kHz mono float32 in [-1, 1]."""
    if encoding.lower() not in _SUPPORTED:
        raise ValueError(f"unsupported encoding: {encoding}")
    if channels < 1:
        raise ValueError("channels must be >= 1")
    pcm = _decode_pcm(raw, encoding)
    pcm = _downmix(pcm, channels)
    pcm = _resample(pcm, in_rate)
    np.clip(pcm, -1.0, 1.0, out=pcm)
    inferred = infer_channel_profile(encoding, in_rate, source=source, profile=profile)
    return pcm, inferred
