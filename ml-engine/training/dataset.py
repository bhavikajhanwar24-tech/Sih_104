"""Anti-spoofing dataset loaders (ASVspoof 2019 LA / 2021 DF / In-the-Wild / Common Voice).

Expects corpora under ``datasets/`` (gitignored). Layout and licences:
``scripts/fetch_datasets.md``.

When a corpus is missing, callers must pass ``synthetic=True`` for smoke tests —
``resolve_splits`` does **not** silently invent a corpus. Do NOT treat synthetic EER
as a published result (Context §3.1).
"""

from __future__ import annotations

import csv
import wave
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Iterable, Iterator, Literal, Optional, Sequence

import numpy as np
from numpy.typing import NDArray

Label = Literal[0, 1]  # 0 = bonafide, 1 = spoof
SR = 16000

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASETS = ROOT.parent / "datasets"
if not DEFAULT_DATASETS.is_dir():
    DEFAULT_DATASETS = ROOT / "datasets"

# Mozilla Common Voice locales used for fairness (§13.5).
CV_LOCALES: tuple[str, ...] = ("hi", "mr", "bn", "ta", "te")
INDO_ARYAN = frozenset({"hi", "bn", "mr", "gu", "pa"})
DRAVIDIAN = frozenset({"ta", "te", "kn", "ml"})
AGE_YOUNG = frozenset({"teens", "twenties", "thirties"})
AGE_SENIOR = frozenset({"sixties", "seventies", "eighties", "nineties"})


@dataclass(frozen=True)
class Sample:
    path: Path
    label: Label
    subset: str
    corpus: str
    attack_id: str = "-"
    speaker_id: str = "-"
    language: str = "-"
    gender: str = "-"
    age: str = "-"
    source: str = "disk"  # disk | synthetic


def corpora_present(root: Optional[Path] = None) -> dict[str, bool]:
    """Cheap presence probe for harness scripts (no full protocol parse)."""
    root = Path(root) if root else DEFAULT_DATASETS
    la = root / "ASVspoof2019" / "LA"
    if not la.is_dir():
        la = root / "ASVspoof2019_LA"
    df = root / "ASVspoof2021" / "DF"
    if not df.is_dir():
        df = root / "ASVspoof2021_DF"
    itw = root / "release_in_the_wild"
    if not itw.is_dir():
        itw = root / "in_the_wild"
    cv = _common_voice_root(root)
    return {
        "asvspoof2019_la": la.is_dir(),
        "asvspoof2021_df": df.is_dir(),
        "in_the_wild": itw.is_dir() and (itw / "meta.csv").is_file(),
        "common_voice": cv is not None,
    }


def primary_train_ready(root: Optional[Path] = None) -> bool:
    """True when ASVspoof 2019 LA train+dev protocols + audio are loadable."""
    root = Path(root) if root else DEFAULT_DATASETS
    train = load_asvspoof2019_la(root, "train", limit=2)
    dev = load_asvspoof2019_la(root, "dev", limit=2)
    return bool(train and dev)


def language_family(language: str) -> str:
    lang = (language or "-").lower().split("-")[0]
    if lang in INDO_ARYAN:
        return "indo_aryan"
    if lang in DRAVIDIAN:
        return "dravidian"
    return "unspecified"


def fairness_tags(sample: Sample) -> dict[str, str]:
    """Derive §13.5 group tags from real metadata only — never invent labels."""
    tags: dict[str, str] = {}
    fam = language_family(sample.language)
    if fam != "unspecified":
        tags["language_family"] = fam
    g = (sample.gender or "-").lower().strip()
    if g in ("female", "f", "woman"):
        tags["gender"] = "gender_f"
    elif g in ("male", "m", "man"):
        tags["gender"] = "gender_m"
    age = (sample.age or "-").lower().strip()
    if age in AGE_YOUNG:
        tags["age"] = "age_young"
    elif age in AGE_SENIOR:
        tags["age"] = "age_senior"
    return tags


def primary_fairness_group(sample: Sample) -> str:
    """Single group id for portal chart: prefer language family, then gender, then age."""
    tags = fairness_tags(sample)
    for key in ("language_family", "gender", "age"):
        if key in tags:
            return tags[key]
    return "unspecified"


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
    """Load mono float32 audio; supports WAV (stdlib) and FLAC/other via soundfile."""
    suffix = path.suffix.lower()
    if suffix == ".wav":
        try:
            audio, sr = _read_wav(path)
        except Exception:
            import soundfile as sf

            audio, sr = sf.read(str(path), dtype="float32", always_2d=False)
            if getattr(audio, "ndim", 1) > 1:
                audio = np.mean(audio, axis=1)
    else:
        import soundfile as sf

        audio, sr = sf.read(str(path), dtype="float32", always_2d=False)
        if getattr(audio, "ndim", 1) > 1:
            audio = np.mean(audio, axis=1)
    audio = np.asarray(audio, dtype=np.float32)
    if int(sr) == target_sr:
        return audio
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


def _resolve_audio_file(audio_dir: Path, file_id: str) -> Optional[Path]:
    for ext in (".flac", ".wav", ".FLAC", ".WAV"):
        path = audio_dir / f"{file_id}{ext}"
        if path.is_file():
            return path
    # Some mirrors nest by subfolder.
    for cand in audio_dir.rglob(f"{file_id}.flac"):
        return cand
    for cand in audio_dir.rglob(f"{file_id}.wav"):
        return cand
    return None


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
    # Alternate protocol filenames used by some mirrors.
    if not proto.is_file():
        for alt in proto_dir.glob(f"*{subset}*"):
            if alt.suffix == ".txt":
                proto = alt
                break
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
            path = _resolve_audio_file(audio_dir, file_id)
            if path is None:
                continue
            samples.append(
                Sample(
                    path,
                    label,
                    subset,
                    "asvspoof2019_la",
                    attack_id=system,
                    speaker_id=speaker,
                    source="disk",
                )
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
        audio_dir = df / "ASVspoof2021_DF_eval" / "wav"
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
            speaker = parts[0]
            file_id = parts[1] if len(parts) > 1 else parts[0]
            system = parts[3] if len(parts) > 3 else "-"
            label_s = parts[-1].lower()
            label: Label = 0 if label_s == "bonafide" else 1
            path = _resolve_audio_file(audio_dir, file_id)
            if path is None:
                continue
            samples.append(
                Sample(
                    path,
                    label,
                    "eval",
                    "asvspoof2021_df",
                    attack_id=system,
                    speaker_id=speaker,
                    source="disk",
                )
            )
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
                    speaker_id=row.get("speaker", "-") or "-",
                    source="disk",
                )
            )
            if limit is not None and len(samples) >= limit:
                break
    return samples


def _common_voice_root(root: Path) -> Optional[Path]:
    """Locate a Common Voice tree under datasets/."""
    candidates = [
        root / "common_voice",
        root / "cv-corpus",
        root / "mozilla_common_voice",
    ]
    for c in candidates:
        if c.is_dir():
            return c
    # cv-corpus-17.0-2024-03-22 style
    for c in sorted(root.glob("cv-corpus*")):
        if c.is_dir():
            return c
    return None


def _cv_locale_dir(cv_root: Path, locale: str) -> Optional[Path]:
    direct = cv_root / locale
    if direct.is_dir():
        return direct
    # Nested: cv-corpus-X/hi/...
    nested = cv_root / locale
    if nested.is_dir():
        return nested
    for cand in cv_root.rglob(locale):
        if cand.is_dir() and ((cand / "clips").is_dir() or list(cand.glob("*.tsv"))):
            return cand
    return None


def _cv_clips_dir(locale_dir: Path) -> Path:
    clips = locale_dir / "clips"
    return clips if clips.is_dir() else locale_dir


def _pick_cv_tsv(locale_dir: Path) -> Optional[Path]:
    for name in ("validated.tsv", "train.tsv", "other.tsv", "dev.tsv", "test.tsv"):
        p = locale_dir / name
        if p.is_file():
            return p
    tsvs = sorted(locale_dir.glob("*.tsv"))
    return tsvs[0] if tsvs else None


def load_common_voice(
    root: Path,
    *,
    locales: Sequence[str] = CV_LOCALES,
    limit: Optional[int] = None,
    limit_per_locale: Optional[int] = None,
) -> list[Sample]:
    """Mozilla Common Voice bonafide clips with language / gender / age from TSV.

    Expected layout (see ``scripts/fetch_datasets.md``)::

        datasets/common_voice/{hi,mr,bn,ta,te}/
          clips/*.mp3|*.wav
          validated.tsv   (or train.tsv)
    """
    cv_root = _common_voice_root(root)
    if cv_root is None:
        return []

    samples: list[Sample] = []
    per_locale = limit_per_locale
    if per_locale is None and limit is not None:
        per_locale = max(1, limit // max(1, len(locales)))

    for locale in locales:
        loc_dir = _cv_locale_dir(cv_root, locale)
        if loc_dir is None:
            continue
        tsv = _pick_cv_tsv(loc_dir)
        if tsv is None:
            continue
        clips_dir = _cv_clips_dir(loc_dir)
        n_loc = 0
        with tsv.open(encoding="utf-8", newline="") as f:
            reader = csv.DictReader(f, delimiter="\t")
            for row in reader:
                rel = (row.get("path") or row.get("clip") or "").strip()
                if not rel:
                    continue
                path = clips_dir / rel
                if not path.is_file():
                    # Some exports put basename only.
                    path = clips_dir / Path(rel).name
                if not path.is_file():
                    continue
                gender = (row.get("gender") or "-").strip() or "-"
                age = (row.get("age") or "-").strip() or "-"
                speaker = (row.get("client_id") or row.get("clientId") or "-").strip() or "-"
                lang = (row.get("locale") or locale).strip() or locale
                samples.append(
                    Sample(
                        path,
                        0,  # Common Voice is bonafide-only for fairness probe
                        "eval",
                        "common_voice",
                        speaker_id=speaker[:32],
                        language=lang.split("-")[0].lower(),
                        gender=gender.lower() if gender != "-" else "-",
                        age=age.lower() if age != "-" else "-",
                        source="disk",
                    )
                )
                n_loc += 1
                if per_locale is not None and n_loc >= per_locale:
                    break
                if limit is not None and len(samples) >= limit:
                    return samples
    return samples


def speaker_disjoint_cap(
    samples: Sequence[Sample],
    *,
    limit: Optional[int],
    forbidden_speakers: Optional[set[str]] = None,
) -> list[Sample]:
    """Cap samples while keeping speaker sets disjoint from ``forbidden_speakers``.

    ASVspoof protocols are already speaker-disjoint across train/dev/eval; this
    helper preserves that property under ``--limit`` subsample.
    """
    forbidden = set(forbidden_speakers or ())
    out: list[Sample] = []
    seen_spk: set[str] = set()
    for s in samples:
        spk = s.speaker_id or "-"
        if spk in forbidden and spk != "-":
            continue
        out.append(s)
        if spk != "-":
            seen_spk.add(spk)
        if limit is not None and len(out) >= limit:
            break
    return out


def assert_speaker_disjoint(*groups: Sequence[Sample]) -> None:
    """Raise if any speaker_id (other than '-') appears in more than one group."""
    assigned: dict[str, int] = {}
    for gi, group in enumerate(groups):
        for s in group:
            spk = s.speaker_id or "-"
            if spk == "-":
                continue
            if spk in assigned and assigned[spk] != gi:
                raise ValueError(
                    f"speaker-disjoint violation: speaker={spk} in groups "
                    f"{assigned[spk]} and {gi}"
                )
            assigned[spk] = gi


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
    """Build a tiny bonafide/spoof corpus for pipeline smoke tests.

    Every Sample has ``source='synthetic'``. Never cite these EERs as field results.
    """
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
            # Distinct synthetic speakers so speaker-disjoint checks still pass.
            spk = f"synth_{corpus}_{subset}_{i // 4}"
            samples.append(
                Sample(
                    path,
                    label,
                    subset,
                    corpus,
                    attack_id="synth_voc" if label else "-",
                    speaker_id=spk,
                    source="synthetic",
                )
            )
        return samples

    out["train"] = _make("train", n_train, "synthetic_la", wild=False)
    out["dev"] = _make("dev", n_dev, "synthetic_la", wild=False)
    out["eval"] = _make("eval", n_eval, "synthetic_la", wild=False)
    out["itw"] = _make("eval", n_itw, "synthetic_itw", wild=True)
    out["df2021"] = list(out["eval"])  # smoke proxy for DF cell
    out["common_voice"] = []
    return out


def resolve_splits(
    datasets_root: Path,
    *,
    limit: Optional[int] = None,
    synthetic: bool = False,
    synthetic_dir: Optional[Path] = None,
    require_speaker_disjoint: bool = True,
) -> dict[str, list[Sample]]:
    """Load real corpora when present.

    If ``synthetic`` is False and train/dev are missing, returns empty train/dev
    (callers must stop or pass ``--synthetic``). Never silently fabricates a corpus.
    """
    datasets_root = Path(datasets_root)

    if synthetic:
        syn_root = synthetic_dir or (datasets_root / "_synthetic_antispoof")
        n = limit or 200
        return generate_synthetic_corpus(
            syn_root,
            n_train=n,
            n_dev=max(40, n // 3),
            n_eval=max(40, n // 3),
            n_itw=max(40, n // 3),
        )

    train = load_asvspoof2019_la(datasets_root, "train", limit=None)
    dev = load_asvspoof2019_la(datasets_root, "dev", limit=None)
    ev = load_asvspoof2019_la(datasets_root, "eval", limit=None)
    df = load_asvspoof2021_df(datasets_root, limit=None)
    itw = load_in_the_wild(datasets_root, limit=None)
    cv = load_common_voice(datasets_root, limit=limit)

    if require_speaker_disjoint and train and dev:
        try:
            assert_speaker_disjoint(train, dev, ev or [])
        except ValueError as ex:
            # Protocols should be disjoint; if a broken mirror mixes them, refuse.
            raise RuntimeError(f"ASVspoof speaker-disjoint check failed: {ex}") from ex

    # Cap after disjoint check so --limit cannot re-introduce overlap.
    train_c = speaker_disjoint_cap(train, limit=limit)
    forbidden = {s.speaker_id for s in train_c if s.speaker_id != "-"}
    dev_c = speaker_disjoint_cap(dev, limit=limit, forbidden_speakers=forbidden)
    forbidden |= {s.speaker_id for s in dev_c if s.speaker_id != "-"}
    ev_c = speaker_disjoint_cap(ev, limit=limit, forbidden_speakers=forbidden)
    df_c = speaker_disjoint_cap(df, limit=limit)
    itw_c = speaker_disjoint_cap(itw, limit=limit)

    return {
        "train": train_c,
        "dev": dev_c,
        "eval": ev_c,
        "df2021": df_c,
        "itw": itw_c,
        "common_voice": cv,
    }


def mark_synthetic(samples: Iterable[Sample]) -> list[Sample]:
    return [replace(s, source="synthetic") if s.source != "synthetic" else s for s in samples]


def iter_balanced(samples: Iterable[Sample], limit: Optional[int] = None) -> Iterator[Sample]:
    bona = [s for s in samples if s.label == 0]
    spoof = [s for s in samples if s.label == 1]
    n = min(len(bona), len(spoof))
    if limit is not None:
        n = min(n, limit // 2)
    for i in range(n):
        yield bona[i]
        yield spoof[i]
