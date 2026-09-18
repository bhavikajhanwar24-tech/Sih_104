#!/usr/bin/env python3
"""Enrol a Voice Passport embedding and POST it to the Java passport endpoint.

Usage:
  python scripts/enrol_speaker.py --wav clip.wav --profile-id EMP-10492
  python scripts/enrol_speaker.py --wav clip.wav --profile-id EMP-10492 --profile narrowband
  python scripts/enrol_speaker.py --wav a.wav b.wav --profile-id EMP-10492 --profile wideband

``--profile narrowband`` µ-law-degrades the audio first so a CFO can hold both a
wideband and a narrowband passport (Context §10.5 / §12 channel-mismatch caveat).
"""

from __future__ import annotations

import argparse
import base64
import json
import shutil
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

# Allow running as `python scripts/enrol_speaker.py` from ml-engine/
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.config import settings  # noqa: E402
from app.modules import speaker as speaker_mod  # noqa: E402
from app.types import ChannelProfile  # noqa: E402

PROFILE_MAP = {
    "narrowband": ChannelProfile.PSTN_NARROWBAND,
    "nb": ChannelProfile.PSTN_NARROWBAND,
    "pstn": ChannelProfile.PSTN_NARROWBAND,
    "wideband": ChannelProfile.WEBRTC_WIDEBAND,
    "wb": ChannelProfile.WEBRTC_WIDEBAND,
    "voip": ChannelProfile.VOIP_WIDEBAND,
    "webrtc": ChannelProfile.WEBRTC_WIDEBAND,
}


def _ffmpeg() -> str:
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    import imageio_ffmpeg

    return imageio_ffmpeg.get_ffmpeg_exe()


def _read_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        nch = w.getnchannels()
        sw = w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if sw == 2:
        pcm = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    else:
        raise ValueError(f"unsupported sample width {sw} in {path}")
    if nch > 1:
        pcm = pcm.reshape(-1, nch).mean(axis=1)
    return pcm, sr


def _write_wav(path: Path, audio: np.ndarray, sr: int) -> None:
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def _mulaw_degrade(audio: np.ndarray, sr: int) -> np.ndarray:
    """G.711 µ-law round-trip → 16 kHz (narrowband enrolment)."""
    ffmpeg = _ffmpeg()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        src, mid, out = root / "src.wav", root / "mulaw.wav", root / "out.wav"
        _write_wav(src, audio, sr)
        subprocess.run(
            [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(src),
             "-ar", "8000", "-ac", "1", "-c:a", "pcm_mulaw", str(mid)],
            check=True,
        )
        subprocess.run(
            [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(mid),
             "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(out)],
            check=True,
        )
        degraded, out_sr = _read_wav(out)
        assert out_sr == 16000
        return degraded


def _load_and_concat(paths: list[Path]) -> tuple[np.ndarray, int]:
    chunks: list[np.ndarray] = []
    sr0: int | None = None
    for p in paths:
        audio, sr = _read_wav(p)
        if sr0 is None:
            sr0 = sr
        elif sr != sr0:
            # Resample to first file's rate via linear interp.
            n_out = int(round(audio.size * sr0 / float(sr)))
            x = np.linspace(0.0, 1.0, audio.size)
            audio = np.interp(np.linspace(0.0, 1.0, n_out), x, audio).astype(np.float32)
        chunks.append(audio)
    assert sr0 is not None
    return np.concatenate(chunks), sr0


def main() -> int:
    parser = argparse.ArgumentParser(description="Enrol ECAPA Voice Passport embedding")
    parser.add_argument("--wav", nargs="+", required=True, help="One or more enrolment WAVs")
    parser.add_argument("--profile-id", required=True, help="Employee / caller profile id")
    parser.add_argument(
        "--profile",
        default="wideband",
        choices=sorted(PROFILE_MAP.keys()),
        help="Channel profile for this enrolment (narrowband applies µ-law degrade)",
    )
    parser.add_argument("--name", default="", help="Caller display name")
    parser.add_argument("--role", default="employee", help="Claimed role")
    parser.add_argument(
        "--url",
        default=settings.java_passport_url,
        help="Java passport register URL",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print embedding payload; do not POST",
    )
    args = parser.parse_args()

    paths = [Path(p) for p in args.wav]
    for p in paths:
        if not p.is_file():
            print(f"ERROR: missing WAV {p}", file=sys.stderr)
            return 2

    channel = PROFILE_MAP[args.profile]
    audio, sr = _load_and_concat(paths)
    if channel == ChannelProfile.PSTN_NARROWBAND:
        print("Applying µ-law narrowband degrade for enrolment…")
        audio = _mulaw_degrade(audio, sr)
        sr = 16000

    print(f"Loading ECAPA ({speaker_mod.MODEL_SOURCE})…")
    info = speaker_mod.warmup()
    print(
        f"warmup_ms={info['warmup_ms']:.1f} embed_latency_ms={info['embed_latency_ms']:.1f} "
        f"stride={info['window_stride']}"
    )

    emb, reason = speaker_mod.embed(audio, sr)
    if emb is None:
        print(f"ERROR: embed failed: {reason}", file=sys.stderr)
        return 1

    fingerprint = base64.b64encode(emb.astype(np.float32).tobytes()).decode("ascii")
    payload = {
        "callerId": args.profile_id,
        "callerName": args.name or args.profile_id,
        "claimedRole": args.role,
        "consentGranted": True,
        "voiceFingerprint": json.dumps(
            {
                "dim": int(emb.size),
                "channelProfile": channel.value,
                "modelId": speaker_mod.MODEL_SOURCE,
                "embeddingB64": fingerprint,
            }
        ),
    }

    print(f"profileId={args.profile_id} channel={channel.value} dim={emb.size}")
    if args.dry_run:
        print(json.dumps({**payload, "voiceFingerprint": "<omitted>"}, indent=2))
        print(f"embedding_norm={float(np.linalg.norm(emb)):.4f}")
        return 0

    try:
        import urllib.request

        req = urllib.request.Request(
            args.url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8")
            print(f"POST {args.url} → {resp.status}")
            print(body)
    except Exception as exc:
        print(f"ERROR: POST failed: {exc}", file=sys.stderr)
        print("Hint: start the Java backend, or pass --dry-run to skip the POST.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
