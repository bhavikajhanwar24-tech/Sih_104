"""Anti-spoofing dataset loaders (ASVspoof 2019 LA / 2021 DF / In-the-Wild).

Expects corpora under ``datasets/`` (gitignored). When a corpus is missing, use
``--synthetic`` / ``load_synthetic`` for smoke tests — do NOT treat synthetic EER
as a published result (Context §3.1).
"""

from __future__ import annotations

import csv
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, Literal, Optional

import numpy as np
from numpy.typing import NDArray

Label = Literal[0, 1]  # 0 = bonafide, 1 = spoof
SR = 16000

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASETS = ROOT.parent / "datasets"
if not DEFAULT_DATASETS.is_dir():
    DEFAULT_DATASETS = ROOT / "datasets"


@dataclass(frozen=True)
class Sample:
    path: Path
    label: Label
    subset: str
    corpus: str
    attack_id: str = "-"
    speaker_id: str = "-"


def _read_wav(path: Path) -> tuple[NDArray[np.floating], int]:
    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        nch = w.getnchannels()
        sw = w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if sw != 2:
        raise ValueError(f"expected 16-bit PCM in {path}, got sampwidth={sw}")
    pcm = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    if nch > 1:
        pcm = pcm.reshape(-1, nch).mean(axis=1)
    return pcm, sr


def load_audio(path: Path, target_sr: int = SR) -> NDArray[np.floating]:
    audio, sr = _read_wav(path)
    if sr == target_sr:
        return audio.astype(np.float32)
    n_out = int(round(audio.size * target_sr / float(sr)))
    x = np.linspace(0.0, 1.0, audio.size, dtype=np.float64)
    return np.interp(np.linspace(0.0, 1.0, n_out), x, audio).astype(np.float32)


def _write_wav(path: Path, audio: NDArray[np.floating], sr: int = SR) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def _parse_asvspoof2019_protocol(line: str) -> Optional[tuple[str, str, str, Label]]:
    # SPEAKER FILE - SYSTEM LABEL   e.g. LA_0079 LA_T_1138215 - - bonafide
    parts = line.strip().split()
    if len(parts) < 5:
        return None
    speaker, file_id, _dash, system, label_s = parts[0], parts[1], parts[2], parts[3], parts[4]
    label: Label = 0 if label_s.lower() == "bonafide" else 1
    return speaker, file_id, system, label


def load_asvspoof2019_la(
    root: Path,
    subset: Literal["train", "dev", "eval"],
    *,
    limit: Optional[int] = None,
) -> list[Sample]:
    """ASVspoof 2019 Logical Access.

    Expected layout::
        datasets/ASVspoof2019/LA/
          ASVspoof2019_LA_cm_protocols/
          ASVspoof2019_LA_train/flac/   (or wav/)
          ASVspoof2019_LA_dev/...
          ASVspoof2019_LA_eval/...
    """
    la = root / "ASVspoof2019" / "LA"
    if not la.is_dir():
        la = root / "ASVspoof2019_LA"
    proto_dir = la / "ASVspoof2019_LA_cm_protocols"
    if not proto_dir.is_dir():
        proto_dir = la / "protocols"
    proto_map = {
        "train": "ASVspoof2019.LA.cm.train.trn.txt",
        "dev": "ASVspoof2019.LA.cm.dev.trl.txt",
        "eval": "ASVspoof2019.LA.cm.eval.trl.txt",
    }
    proto = proto_dir / proto_map[subset]
    audio_dir = la / f"ASVspoof2019_LA_{subset}"
    for cand in (audio_dir / "flac", audio_dir / "wav", audio_dir):
        if cand.is_dir():
            audio_dir = cand
            break
    if not proto.is_file():
        return []

    samples: list[Sample] = []
    with proto.open(encoding="utf-8") as f:
        for line in f:
            parsed = _parse_asvspoof2019_protocol(line)
            if parsed is None:
                continue
            speaker, file_id, system, label = parsed
            path = audio_dir / f"{file_id}.flac"
            if not path.is_file():
                path = audio_dir / f"{file_id}.wav"
            if not path.is_file():
                continue
            samples.append(
                Sample(path, label, subset, "asvspoof2019_la", attack_id=system, speaker_id=speaker)
            )
            if limit is not None and len(samples) >= limit:
                break
    return samples


def load_asvspoof2021_df(
    root: Path,
    *,
    limit: Optional[int] = None,
) -> list[Sample]:
    """ASVspoof 2021 DeepFake eval (codec/compression stress)."""
    df = root / "ASVspoof2021" / "DF"
    if not df.is_dir():
        df = root / "ASVspoof2021_DF"
    proto = df / "ASVspoof2021_DF_cm_protocols" / "ASVspoof2021.DF.cm.eval.trl.txt"
    if not proto.is_file():
        # Alternate naming used by some mirrors.
        for p in df.rglob("*eval*.trl.txt"):
            proto = p
            break
    audio_dir = df / "ASVspoof2021_DF_eval" / "flac"
    if not audio_dir.is_dir():
        audio_dir = df / "flac"
    if not proto.is_file():
        return []

    samples: list[Sample] = []
    with proto.open(encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split()
            if len(parts) < 2:
                continue
            file_id = parts[1] if len(parts) > 1 else parts[0]
            label_s = parts[-1].lower()
            label: Label = 0 if label_s == "bonafide" else 1
            path = audio_dir / f"{file_id}.flac"
            if not path.is_file():
                path = audio_dir / f"{file_id}.wav"
            if not path.is_file():
                continue
            samples.append(Sample(path, label, "eval", "asvspoof2021_df"))
            if limit is not None and len(samples) >= limit:
                break
    return samples


def load_in_the_wild(
    root: Path,
    *,
    limit: Optional[int] = None,
) -> list[Sample]:
    """In-the-Wild (Müller et al.) — real deepfake / genuine web audio.

    Expected::
        datasets/release_in_the_wild/
          meta.csv   (file,speaker,label)
          *.wav / *.flac
    """
    itw = root / "release_in_the_wild"
    if not itw.is_dir():
        itw = root / "in_the_wild"
    meta = itw / "meta.csv"
    if not meta.is_file():
        return []
    samples: list[Sample] = []
    with meta.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            file_id = row.get("file") or row.get("filename") or row.get("path")
            if not file_id:
                continue
            label_s = (row.get("label") or row.get("spoof") or "").lower()
            label: Label = 0 if label_s in ("bonafide", "genuine", "real", "0") else 1
            path = itw / file_id
            if not path.is_file():
                path = itw / f"{file_id}.wav"
            if not path.is_file():
                path = itw / f"{file_id}.flac"
            if not path.is_file():
                continue
            samples.append(
                Sample(
                    path,
                    label,
                    "eval",
                    "in_the_wild",
                    speaker_id=row.get("speaker", "-"),
                )
            )
            if limit is not None and len(samples) >= limit:
                break
    return samples


def _speech_like(rng: np.random.Generator, seconds: float, f0: float) -> NDArray[np.floating]:
    n = int(SR * seconds)
    t = np.arange(n, dtype=np.float64) / SR
    audio = np.zeros(n, dtype=np.float64)
    for k in range(1, 8):
        audio += (0.28 / k) * np.sin(2.0 * np.pi * f0 * k * t + rng.uniform(0, 1))
    audio += 0.05 * rng.standard_normal(n)
    env = 0.5 + 0.5 * np.sin(2.0 * np.pi * 3.0 * t)
    audio *= env
    peak = np.max(np.abs(audio)) + 1e-12
    return (audio / peak * 0.7).astype(np.float32)


def _vocoder_artifact(audio: NDArray[np.floating], rng: np.random.Generator) -> NDArray[np.floating]:
    """Cheap 'spoof' transform: magnitude-only STFT + coarse quantization (vocoder-ish)."""
    n_fft, hop = 512, 128
    window = np.hanning(n_fft)
    if audio.size < n_fft:
        audio = np.pad(audio, (0, n_fft - audio.size))
    n_frames = 1 + (audio.size - n_fft) // hop
    frames = np.stack([audio[i * hop : i * hop + n_fft] * window for i in range(n_frames)])
    spec = np.fft.rfft(frames, axis=1)
    mag = np.abs(spec)
    # Quantise magnitudes (codec/vocoder staircase).
    scale = np.percentile(mag, 95) + 1e-12
    mag_q = np.round(mag / scale * 24.0) / 24.0 * scale
    # Random phase (Griffin-Lim-ish single shot).
    phase = rng.uniform(-np.pi, np.pi, size=mag_q.shape)
    rec = np.fft.irfft(mag_q * np.exp(1j * phase), n=n_fft, axis=1)
    out = np.zeros(audio.size, dtype=np.float64)
    for i in range(n_frames):
        out[i * hop : i * hop + n_fft] += rec[i] * window
    peak = np.max(np.abs(out)) + 1e-12
    return (out / peak * 0.7).astype(np.float32)


def generate_synthetic_corpus(
    out_dir: Path,
    *,
    n_train: int = 200,
    n_dev: int = 80,
    n_eval: int = 80,
    n_itw: int = 80,
    seed: int = 0,
) -> dict[str, list[Sample]]:
    """Build a tiny bonafide/spoof corpus for pipeline smoke tests."""
    rng = np.random.default_rng(seed)
    out: dict[str, list[Sample]] = {}

    def _make(subset: str, n: int, corpus: str, wild: bool) -> list[Sample]:
        samples: list[Sample] = []
        d = out_dir / corpus / subset
        d.mkdir(parents=True, exist_ok=True)
        for i in range(n):
            label: Label = 0 if i % 2 == 0 else 1
            f0 = float(rng.uniform(90, 220 if wild else 180))
            seconds = float(rng.uniform(1.8, 2.6))
            audio = _speech_like(rng, seconds, f0)
            if wild:
                audio = audio + 0.08 * rng.standard_normal(audio.size).astype(np.float32)
            if label == 1:
                audio = _vocoder_artifact(audio, rng)
                if wild:
                    # Domain shift: extra low-pass + gain (ITW is harder — Context §3.1).
                    audio = audio.copy()
                    # crude 1-pole LPF
                    a = 0.92
                    for j in range(1, audio.size):
                        audio[j] = a * audio[j - 1] + (1 - a) * audio[j]
            elif wild:
                # ITW bonafide also shifts (room noise / band-limit) so the detector
                # cannot rely on train-domain cues — expect higher EER (§3.1).
                audio = audio + 0.12 * rng.standard_normal(audio.size).astype(np.float32)
                a = 0.88
                for j in range(1, audio.size):
                    audio[j] = a * audio[j - 1] + (1 - a) * audio[j]
                peak = np.max(np.abs(audio)) + 1e-12
                audio = (audio / peak * 0.7).astype(np.float32)
            path = d / f"{subset}_{i:04d}_{'spoof' if label else 'bona'}.wav"
            _write_wav(path, audio)
            samples.append(Sample(path, label, subset, corpus, attack_id="synth_voc" if label else "-"))
        return samples

    out["train"] = _make("train", n_train, "synthetic_la", wild=False)
    out["dev"] = _make("dev", n_dev, "synthetic_la", wild=False)
    out["eval"] = _make("eval", n_eval, "synthetic_la", wild=False)
    out["itw"] = _make("eval", n_itw, "synthetic_itw", wild=True)
    return out


def resolve_splits(
    datasets_root: Path,
    *,
    limit: Optional[int] = None,
    synthetic: bool = False,
    synthetic_dir: Optional[Path] = None,
) -> dict[str, list[Sample]]:
    """Load real corpora when present; otherwise (or if ``synthetic``) build a smoke set."""
    if not synthetic:
        train = load_asvspoof2019_la(datasets_root, "train", limit=limit)
        dev = load_asvspoof2019_la(datasets_root, "dev", limit=limit)
        ev = load_asvspoof2019_la(datasets_root, "eval", limit=limit)
        df = load_asvspoof2021_df(datasets_root, limit=limit)
        itw = load_in_the_wild(datasets_root, limit=limit)
        if train and dev:
            return {
                "train": train,
                "dev": dev,
                "eval": ev,
                "df2021": df,
                "itw": itw,
            }

    syn_root = synthetic_dir or (datasets_root / "_synthetic_antispoof")
    n = limit or 200
    return generate_synthetic_corpus(
        syn_root,
        n_train=n,
        n_dev=max(40, n // 3),
        n_eval=max(40, n // 3),
        n_itw=max(40, n // 3),
    )


def iter_balanced(samples: Iterable[Sample], limit: Optional[int] = None) -> Iterator[Sample]:
    bona = [s for s in samples if s.label == 0]
    spoof = [s for s in samples if s.label == 1]
    n = min(len(bona), len(spoof))
    if limit is not None:
        n = min(n, limit // 2)
    for i in range(n):
        yield bona[i]
        yield spoof[i]
