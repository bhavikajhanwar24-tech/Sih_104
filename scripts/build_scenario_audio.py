#!/usr/bin/env python3
"""Build 16 kHz mono scenario WAVs + MANIFEST.json (Context §14 / P11.1).

Bona fide (scenarios 1, 5)
  Trim / peak-normalise real teammate or Common Voice clips from
  ``scenarios/audio/sources/``. Missing sources fail loudly — this script
  never synthesises silent-tone placeholders.

Synthetic test fixtures (scenarios 2, 3, 4)
  Piper TTS only. Labelled ``synthetic`` in the manifest. Do NOT clone a
  real person's voice.

Scenario 6
  Adversarial perturbations (room tone / breath / ~20 dB SNR) applied to
  clip 2 via ``ml-engine/app/modules/adversarial.py`` — no new generation.

Usage (repo root, ml-engine venv)::

  python scripts/build_scenario_audio.py
  python scripts/build_scenario_audio.py --only 2,4,6
  python scripts/build_scenario_audio.py --sources-dir scenarios/audio/sources
"""

from __future__ import annotations

import argparse
import audioop
import hashlib
import json
import logging
import os
import shutil
import struct
import subprocess
import sys
import wave
from pathlib import Path
from typing import Any, Optional

LOG = logging.getLogger("sentinelvoice.build_scenario_audio")

TARGET_RATE = 16_000
TARGET_PEAK = 0.89  # ~−1 dBFS headroom
MIN_DURATION_S = 8.0
DEFAULT_DURATION_S = 28.0

# Expected bona-fide drops (fail loudly if absent when building 1 or 5).
BONA_FIDE_SOURCES = {
    "01-legit-cfo": (
        "01-legit-cfo.source.wav",
        "01-legit-cfo.wav",
        "legit-cfo.wav",
    ),
    "05-false-positive-stress": (
        "05-false-positive-stress.source.wav",
        "05-false-positive-stress.wav",
        "false-positive-stress.wav",
    ),
}

# Piper scripts — synthetic fixtures for OUR detector. Scenario 4 embeds
# Hinglish lexicon tokens (turant, kisi ko mat batana, bail, musibat).
TTS_SCRIPTS: dict[str, dict[str, str]] = {
    "02-deepfake-ceo-wire": {
        "voice": "en_US-lessac-medium",
        "text": (
            "This is Rajesh Kumar, Chief Financial Officer. "
            "I need you to approve an urgent wire transfer of fifty lakh rupees "
            "to the Hong Kong vendor settlement account immediately. "
            "Do not tell anyone in the office. Keep this confidential — "
            "verbal approval only. Process it now before market close."
        ),
        "licence": "synthetic test fixture (Piper TTS); not a voice clone",
    },
    "03-liveness-challenge": {
        "voice": "en_US-lessac-medium",
        "text": (
            "Yes, I can hear you. Please wait. "
            "Amber Falcon seventy two. "
            "Sorry, say that again? Amber Falcon 72. "
            "I already repeated the phrase."
        ),
        "licence": "synthetic test fixture (Piper TTS); not a voice clone",
    },
    "04-hinglish-grandparent": {
        "voice": "en_US-lessac-medium",
        "text": (
            "Dadi, main Rohan bol raha hoon. Main musibat mein hoon. "
            "Bail ke liye turant pachees hazar UPI kar do. "
            "Kisi ko mat batana, manager ko mat bolna. "
            "Jaldi karo, time nahi hai, paise bhejo warna police station."
        ),
        "licence": "synthetic test fixture (Piper TTS); Hinglish lexicon exercise",
    },
}


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_wav_pcm16(path: Path, pcm16: bytes, rate: int = TARGET_RATE) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm16)


def read_wav_mono_pcm16(path: Path) -> tuple[bytes, int]:
    with wave.open(str(path), "rb") as w:
        channels = w.getnchannels()
        sampwidth = w.getsampwidth()
        rate = w.getframerate()
        raw = w.readframes(w.getnframes())
    if sampwidth != 2:
        # Convert 8/24/32-bit via audioop when possible
        if sampwidth == 1:
            raw = audioop.bias(audioop.lin2lin(raw, 1, 2), 2, -128 * 256)
        elif sampwidth == 3:
            # 24-bit → 16-bit
            out = bytearray()
            for i in range(0, len(raw), 3):
                sample = int.from_bytes(raw[i : i + 3], "little", signed=True)
                out += struct.pack("<h", max(-32768, min(32767, sample >> 8)))
            raw = bytes(out)
        elif sampwidth == 4:
            raw = audioop.lin2lin(raw, 4, 2)
        else:
            raise ValueError(f"{path}: unsupported sampwidth={sampwidth}")
    if channels == 2:
        raw = audioop.tomono(raw, 2, 0.5, 0.5)
    elif channels != 1:
        raise ValueError(f"{path}: unsupported channels={channels}")
    if rate != TARGET_RATE:
        raw, _ = audioop.ratecv(raw, 2, 1, rate, TARGET_RATE, None)
        rate = TARGET_RATE
    return raw, rate


def pcm16_to_float(pcm16: bytes):
    import numpy as np

    return np.frombuffer(pcm16, dtype="<i2").astype(np.float32) / 32768.0


def float_to_pcm16(y) -> bytes:
    import numpy as np

    clipped = np.clip(np.asarray(y, dtype=np.float64), -1.0, 1.0)
    return (clipped * 32767.0).astype("<i2").tobytes()


def trim_silence(pcm16: bytes, sr: int = TARGET_RATE, pad_ms: int = 200) -> bytes:
    """Energy-gate trim; keeps short leading/trailing pad."""
    import numpy as np

    y = pcm16_to_float(pcm16)
    if y.size == 0:
        return pcm16
    win = max(1, int(sr * 0.02))
    # Frame RMS
    n_frames = max(1, y.size // win)
    rms = np.array(
        [float(np.sqrt(np.mean(y[i * win : (i + 1) * win] ** 2) + 1e-12)) for i in range(n_frames)],
        dtype=np.float64,
    )
    thresh = max(0.01, float(np.percentile(rms, 20)) * 2.5)
    speech = np.where(rms >= thresh)[0]
    if speech.size == 0:
        return pcm16
    pad = max(0, int(sr * pad_ms / 1000) // win)
    start = max(0, int(speech[0]) - pad) * win
    end = min(y.size, (int(speech[-1]) + 1 + pad) * win)
    return float_to_pcm16(y[start:end])


def peak_normalise(pcm16: bytes, target_peak: float = TARGET_PEAK) -> bytes:
    import numpy as np

    y = pcm16_to_float(pcm16)
    peak = float(np.max(np.abs(y))) if y.size else 0.0
    if peak < 1e-6:
        raise ValueError("audio is silent after trim — refuse to emit a tone placeholder")
    return float_to_pcm16(y * (target_peak / peak))


def ensure_min_duration(pcm16: bytes, min_s: float = MIN_DURATION_S, sr: int = TARGET_RATE) -> bytes:
    need = int(min_s * sr) * 2
    if len(pcm16) >= need:
        return pcm16
    # Repeat with short silence gaps rather than inventing a tone.
    gap = b"\x00" * (sr // 5 * 2)
    out = bytearray(pcm16)
    while len(out) < need:
        out.extend(gap)
        out.extend(pcm16)
    return bytes(out[: max(need, len(pcm16))])


def find_bona_fide_source(sources_dir: Path, stem: str) -> Path:
    names = BONA_FIDE_SOURCES[stem]
    for name in names:
        candidate = sources_dir / name
        if candidate.is_file():
            return candidate
    expected = ", ".join(names)
    raise FileNotFoundError(
        f"Bona-fide source missing for {stem}.\n"
        f"  Looked in: {sources_dir.resolve()}\n"
        f"  Expected one of: {expected}\n"
        f"  Drop a teammate WAV or Common Voice (hi/en) clip there, then re-run.\n"
        f"  This script will NOT generate silent-tone placeholders."
    )


def build_bona_fide(stem: str, sources_dir: Path, out_dir: Path) -> dict[str, Any]:
    src = find_bona_fide_source(sources_dir, stem)
    pcm, _ = read_wav_mono_pcm16(src)
    pcm = trim_silence(pcm)
    pcm = peak_normalise(pcm)
    pcm = ensure_min_duration(pcm, MIN_DURATION_S)
    # Cap very long CV clips for demo pacing (~45 s)
    max_bytes = int(45 * TARGET_RATE) * 2
    if len(pcm) > max_bytes:
        pcm = pcm[:max_bytes]
    out = out_dir / f"{stem}.wav"
    write_wav_pcm16(out, pcm)
    licence_sidecar = src.with_suffix(src.suffix + ".licence.txt")
    licence = "bona_fide — see source; CC-0 if Mozilla Common Voice"
    if licence_sidecar.is_file():
        licence = licence_sidecar.read_text(encoding="utf-8").strip() or licence
    LOG.info("bona_fide %s ← %s (%.1fs)", out.name, src.name, len(pcm) / 2 / TARGET_RATE)
    return {
        "file": f"scenarios/audio/{out.name}",
        "sha256": sha256_file(out),
        "label": "bona_fide",
        "licence": licence,
        "source": str(src.relative_to(repo_root())) if src.is_relative_to(repo_root()) else str(src),
        "sample_rate": TARGET_RATE,
        "duration_s": round(len(pcm) / 2 / TARGET_RATE, 3),
    }


def resolve_piper_bin() -> Path:
    env = os.environ.get("PIPER_BIN", "").strip()
    if env:
        p = Path(env)
        if p.is_file():
            return p
        raise FileNotFoundError(f"PIPER_BIN={env} is not a file")
    which = shutil.which("piper")
    if which:
        return Path(which)
    root = repo_root()
    for candidate in (
        root / "tools" / "piper" / "piper.exe",
        root / "tools" / "piper" / "piper",
        root / "scenarios" / "audio" / "models" / "piper" / "piper.exe",
        root / "scenarios" / "audio" / "models" / "piper" / "piper",
    ):
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(
        "Piper TTS binary not found. Install Piper and either put it on PATH "
        "or set PIPER_BIN. See scenarios/README.md (Piper models)."
    )


def resolve_piper_voice(voice: str, models_dir: Path) -> tuple[Path, Path]:
    """Return (onnx, json) for a Piper voice name like en_US-lessac-medium."""
    onnx = models_dir / f"{voice}.onnx"
    meta = models_dir / f"{voice}.onnx.json"
    if onnx.is_file() and meta.is_file():
        return onnx, meta
    # Nested layout: en/en_US/en_US-lessac-medium/…
    matches = list(models_dir.rglob(f"{voice}.onnx"))
    for m in matches:
        j = Path(str(m) + ".json")
        if not j.is_file():
            j = m.with_suffix(".onnx.json")
        if j.is_file():
            return m, j
    raise FileNotFoundError(
        f"Piper voice model missing: {voice}\n"
        f"  Expected under {models_dir.resolve()}:\n"
        f"    {voice}.onnx + {voice}.onnx.json\n"
        f"  Fetch steps: scenarios/README.md"
    )


def piper_tts(text: str, voice: str, models_dir: Path, out_wav: Path) -> None:
    piper = resolve_piper_bin()
    onnx, _meta = resolve_piper_voice(voice, models_dir)
    out_wav.parent.mkdir(parents=True, exist_ok=True)
    # Piper writes its native rate; we re-read and normalise to 16 kHz mono.
    tmp = out_wav.with_suffix(".piper.wav")
    cmd = [
        str(piper),
        "--model",
        str(onnx),
        "--output_file",
        str(tmp),
    ]
    LOG.info("piper voice=%s → %s", voice, out_wav.name)
    proc = subprocess.run(
        cmd,
        input=text.encode("utf-8"),
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0 or not tmp.is_file():
        err = (proc.stderr or b"").decode("utf-8", errors="replace")
        raise RuntimeError(f"piper failed (exit={proc.returncode}): {err}")
    pcm, _ = read_wav_mono_pcm16(tmp)
    pcm = trim_silence(pcm)
    pcm = peak_normalise(pcm)
    pcm = ensure_min_duration(pcm, MIN_DURATION_S)
    # Stretch short TTS toward demo length by gentle repetition with pauses.
    target = int(DEFAULT_DURATION_S * TARGET_RATE) * 2
    if len(pcm) < target:
        gap = b"\x00" * (TARGET_RATE // 4 * 2)
        buf = bytearray()
        while len(buf) < target:
            if buf:
                buf.extend(gap)
            buf.extend(pcm)
        pcm = bytes(buf[:target])
    write_wav_pcm16(out_wav, pcm)
    tmp.unlink(missing_ok=True)


def build_tts(stem: str, models_dir: Path, out_dir: Path) -> dict[str, Any]:
    spec = TTS_SCRIPTS[stem]
    out = out_dir / f"{stem}.wav"
    piper_tts(spec["text"], spec["voice"], models_dir, out)
    return {
        "file": f"scenarios/audio/{out.name}",
        "sha256": sha256_file(out),
        "label": "synthetic",
        "licence": spec["licence"],
        "source": f"piper:{spec['voice']}",
        "voice": spec["voice"],
        "sample_rate": TARGET_RATE,
        "duration_s": round(_wav_duration_s(out), 3),
        "notes": "synthetic test fixture — not a voice clone of any real person",
    }


def _wav_duration_s(path: Path) -> float:
    with wave.open(str(path), "rb") as w:
        return w.getnframes() / float(w.getframerate())


def build_adversarial_from_02(out_dir: Path) -> dict[str, Any]:
    """Scenario 6 = perturbations on clip 2 only (adversarial.py)."""
    src = out_dir / "02-deepfake-ceo-wire.wav"
    if not src.is_file():
        raise FileNotFoundError(
            f"Scenario 6 needs {src.name} first. Build scenario 2 (Piper) before 6."
        )
    root = repo_root()
    ml = root / "ml-engine"
    if str(ml) not in sys.path:
        sys.path.insert(0, str(ml))
    from app.modules.adversarial import PerturbationConfig, apply  # noqa: WPS433

    pcm, sr = read_wav_mono_pcm16(src)
    y = pcm16_to_float(pcm)
    cfg = PerturbationConfig(
        enabled=True,
        noise_snr_db=20.0,
        reverb_t60_ms=280.0,
        breath_rate_per_min=14.0,
        codec="g711_ulaw",
        band_limit_hz=4000.0,
    )
    rng = __import__("numpy").random.default_rng(42)
    y_out = apply(y, sr, cfg, rng=rng)
    out = out_dir / "06-adversarial-evasion.wav"
    write_wav_pcm16(out, float_to_pcm16(y_out), sr)
    LOG.info(
        "adversarial %s ← %s (snr=20 dB, reverb, breath, µ-law)",
        out.name,
        src.name,
    )
    return {
        "file": f"scenarios/audio/{out.name}",
        "sha256": sha256_file(out),
        "label": "synthetic",
        "licence": "synthetic test fixture — adversarial.py on scenario-2 Piper clip",
        "source": "scenarios/audio/02-deepfake-ceo-wire.wav + adversarial perturbations",
        "perturbations": cfg.to_dict(),
        "sample_rate": TARGET_RATE,
        "duration_s": round(_wav_duration_s(out), 3),
        "notes": "no new generation capability; defensive robustness fixture only",
    }


def write_manifest(out_dir: Path, entries: list[dict[str, Any]]) -> Path:
    manifest = {
        "schema": "sentinelvoice.ScenarioAudioManifest/1",
        "sample_rate_hz": TARGET_RATE,
        "channels": 1,
        "encoding": "pcm_s16le",
        "notes": (
            "WAVs are gitignored (*.wav). Track via this manifest + Git LFS "
            "or attach a release asset. Bona fide vs synthetic labels are honest."
        ),
        "files": sorted(entries, key=lambda e: e["file"]),
    }
    path = out_dir / "MANIFEST.json"
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    LOG.info("wrote %s (%d entries)", path, len(entries))
    return path


def parse_only(raw: Optional[str]) -> set[str]:
    if not raw:
        return {"1", "2", "3", "4", "5", "6"}
    parts = {p.strip().lstrip("0") or "0" for p in raw.replace(" ", "").split(",") if p.strip()}
    allowed = {"1", "2", "3", "4", "5", "6"}
    bad = parts - allowed
    if bad:
        raise SystemExit(f"--only has unknown ids {sorted(bad)}; use 1..6")
    return parts


def main(argv: Optional[list[str]] = None) -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    root = repo_root()
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument(
        "--sources-dir",
        type=Path,
        default=root / "scenarios" / "audio" / "sources",
        help="Bona-fide input WAVs for scenarios 1 and 5",
    )
    p.add_argument(
        "--models-dir",
        type=Path,
        default=root / "scenarios" / "audio" / "models" / "piper",
        help="Piper .onnx voice models",
    )
    p.add_argument(
        "--out-dir",
        type=Path,
        default=root / "scenarios" / "audio",
        help="Output directory for WAVs + MANIFEST.json",
    )
    p.add_argument("--only", default=None, help="Comma list of scenario numbers, e.g. 2,4,6")
    args = p.parse_args(argv)

    only = parse_only(args.only)
    out_dir: Path = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    sources_dir: Path = args.sources_dir
    models_dir: Path = args.models_dir

    entries: list[dict[str, Any]] = []
    errors: list[str] = []

    # Preserve prior manifest entries for scenarios we skip this run.
    prior_path = out_dir / "MANIFEST.json"
    prior_by_file: dict[str, dict[str, Any]] = {}
    if prior_path.is_file():
        try:
            prior = json.loads(prior_path.read_text(encoding="utf-8"))
            for row in prior.get("files", []):
                if isinstance(row, dict) and "file" in row:
                    prior_by_file[row["file"]] = row
        except json.JSONDecodeError:
            LOG.warning("ignoring corrupt prior MANIFEST.json")

    stem_by_num = {
        "1": "01-legit-cfo",
        "2": "02-deepfake-ceo-wire",
        "3": "03-liveness-challenge",
        "4": "04-hinglish-grandparent",
        "5": "05-false-positive-stress",
        "6": "06-adversarial-evasion",
    }

    # Order: TTS 2 before adversarial 6; bona fide independent.
    build_order = ["1", "5", "2", "3", "4", "6"]
    for num in build_order:
        if num not in only:
            stem = stem_by_num[num]
            key = f"scenarios/audio/{stem}.wav"
            if key in prior_by_file:
                entries.append(prior_by_file[key])
            continue
        stem = stem_by_num[num]
        try:
            if num in {"1", "5"}:
                entries.append(build_bona_fide(stem, sources_dir, out_dir))
            elif num in {"2", "3", "4"}:
                entries.append(build_tts(stem, models_dir, out_dir))
            elif num == "6":
                # Ensure clip 2 exists (build inline if --only includes 6 but not 2)
                clip2 = out_dir / "02-deepfake-ceo-wire.wav"
                if not clip2.is_file():
                    if "2" not in only:
                        LOG.info("scenario 6 requested — building scenario 2 first")
                        entries = [e for e in entries if not e["file"].endswith("02-deepfake-ceo-wire.wav")]
                        entries.append(build_tts("02-deepfake-ceo-wire", models_dir, out_dir))
                entries.append(build_adversarial_from_02(out_dir))
        except Exception as exc:
            LOG.error("FAILED scenario %s (%s): %s", num, stem, exc)
            errors.append(f"{num}/{stem}: {exc}")

    # De-dupe by file (scenario 6 may have added clip 2)
    by_file: dict[str, dict[str, Any]] = {}
    for e in entries:
        by_file[e["file"]] = e
    # Keep prior manifest rows when this run skipped or failed a fixture
    for key, row in prior_by_file.items():
        if key in by_file:
            continue
        wav_name = Path(key).name
        if (out_dir / wav_name).is_file():
            by_file[key] = row
        else:
            # Rebuild failed or not attempted — retain prior metadata for honesty
            stem_num = None
            for num, stem in stem_by_num.items():
                if key.endswith(f"{stem}.wav"):
                    stem_num = num
                    break
            if stem_num is not None and stem_num not in only:
                by_file[key] = row

    if by_file or prior_by_file:
        write_manifest(out_dir, list(by_file.values()))
    elif (out_dir / "MANIFEST.json").is_file() and not by_file:
        # Avoid leaving a vacuous empty manifest after a total failure on a fresh tree
        LOG.warning("no fixtures built — MANIFEST.json not updated")

    if errors:
        print("\nBuild finished with errors:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 2

    if not by_file:
        print("Nothing built.", file=sys.stderr)
        return 2

    print(f"OK — {len(by_file)} fixture(s) in {out_dir / 'MANIFEST.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
